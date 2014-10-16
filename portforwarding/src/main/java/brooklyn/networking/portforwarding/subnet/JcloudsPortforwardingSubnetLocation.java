/*
 * Copyright 2013-2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.networking.portforwarding.subnet;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.ConfigKey;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.Entities;
import brooklyn.location.LocationSpec;
import brooklyn.location.NoMachinesAvailableException;
import brooklyn.location.access.BrooklynAccessUtils;
import brooklyn.location.access.PortForwardManager;
import brooklyn.location.jclouds.JcloudsLocation;
import brooklyn.location.jclouds.JcloudsSshMachineLocation;
import brooklyn.location.jclouds.JcloudsUtil;
import brooklyn.networking.subnet.PortForwarder;
import brooklyn.networking.util.ConcurrentReachableAddressFinder;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Networking;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

/** requires zone id and tier id to be specified; shared_network_id optional (but needed if you want to connect!) */
public class JcloudsPortforwardingSubnetLocation extends JcloudsLocation {

    private static final long serialVersionUID = 2447151199192553199L;

    private static final Logger log = LoggerFactory.getLogger(JcloudsPortforwardingSubnetLocation.class);

    /** config on the subnet jclouds location */
    public static final ConfigKey<Long> TIME_BETWEEN_OBTAINS = ConfigKeys.newLongConfigKey("timeBetweenObtains", "time to wait (in milliseconds) between calls to obtain", 0L);

    public static final ConfigKey<PortForwarder> PORT_FORWARDER = ConfigKeys.newConfigKey(PortForwarder.class, "portForwarder");

    /** For preventing concurrent calls to obtain */
    private static final Object mutex = new Object();
    private static long lastObtainTime = -1;

    public interface SubnetAccessMode {}

    // required for port forwarding -- set by location creator (e.g. SubnetTierImpl)
    public static final ConfigKey<PortForwardManager> PORT_FORWARDING_MANAGER = BrooklynAccessUtils.PORT_FORWARDING_MANAGER;

    public JcloudsPortforwardingSubnetLocation() {
    }

    public JcloudsPortforwardingSubnetLocation(JcloudsLocation parent, ConfigBag map) {
        super(MutableMap.copyOf(parent.getLocalConfigBag().getAllConfig()));
        configure(map.getAllConfig());
    }

    public JcloudsPortforwardingSubnetLocation(Map<?,?> properties) {
        super(MutableMap.copyOf(properties));
    }

    protected <T> T getRequiredConfig(ConfigKey<T> key) {
        return checkNotNull(getConfig(key), key.getName());
    }

    @Override
    public JcloudsSshMachineLocation obtain(Map<?,?> flagsIn) throws NoMachinesAvailableException {
        MutableMap<Object, Object> flags2 = MutableMap.builder()
                .putAll(flagsIn)
                .put(JcloudsLocation.USE_PORT_FORWARDING, getConfig(USE_PORT_FORWARDING))
                .put(JcloudsPortforwardingSubnetLocation.PORT_FORWARDER, getConfig(PORT_FORWARDER))
                .build();

        // Throttle to ensure only one call to obtain per X seconds (but calls can overlap)
        log.info("provision - waiting to acquire mutex ("+Thread.currentThread()+")");
        synchronized (mutex) {
            long timeBetweenObtains = getConfig(TIME_BETWEEN_OBTAINS);
            long now = System.currentTimeMillis();
            if (timeBetweenObtains <= 0) {
                // ignore; not constraints on time-between-calls
            } else if (lastObtainTime >= 0 && (now < (lastObtainTime + timeBetweenObtains))) {
                long wait = lastObtainTime + timeBetweenObtains - now;
                log.info("provision - waiting for "+wait+"ms as another obtain call executed recently "+Thread.currentThread());
                Time.sleep(wait);
            } else {
                log.info("provision - contininuing immediately as no other recent call "+Thread.currentThread());
            }
            lastObtainTime = now;
        }

        // And finally obtain the machine
        JcloudsSshMachineLocation m = super.obtain(flags2);

        return m;
    }

    // TODO Remove duplication from super's JcloudsLocation.createJcloudsSshMachineLocation
    // the todos/fixmes in this method are copied from there; they should be addressed in core brooklyn
    @Override
    protected JcloudsSshMachineLocation createJcloudsSshMachineLocation(ComputeService computeService, NodeMetadata node, String vmHostname, Optional<HostAndPort> sshHostAndPort, ConfigBag setup) throws IOException {
        String user = getUser(setup);
        Map<?,?> sshConfig = extractSshConfig(setup, node);
        String nodeAvailabilityZone = extractAvailabilityZone(setup, node);
        String nodeRegion = extractRegion(setup, node);
        if (nodeRegion == null) {
            // e.g. rackspace doesn't have "region", so rackspace-uk is best we can say (but zone="LON")
            nodeRegion = extractProvider(setup, node);
        }

        String address = sshHostAndPort.isPresent() ? sshHostAndPort.get().getHostText() : vmHostname;
        try {
            Networking.getInetAddressWithFixedName(address);
            // fine, it resolves
        } catch (Exception e) {
            // occurs if an unresolvable hostname is given as vmHostname
            // TODO cleanup use of getPublicHostname so its semantics are clearer, returning reachable hostname or ip, and 
            // do this check/fix there instead of here!
            Exceptions.propagateIfFatal(e);
            if ("false".equalsIgnoreCase(setup.get(WAIT_FOR_SSHABLE))) {
                // Don't want to wait for (or don't expect) VM to be ssh'able, so just check IP reachabilty; 
                // but don't fail if can't access it at all.
                // TODO What is a sensible time to wait?
                LOG.debug("Could not resolve reported address '"+address+"' for "+vmHostname+" ("+setup.getDescription()+"/"+node+"), waitForSshable=false, so requesting reachable address");
                Iterable<String> addresses = Iterables.concat(node.getPublicAddresses(), node.getPrivateAddresses());
                ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());
                try {
                    address = new ConcurrentReachableAddressFinder(executor).findReachable(addresses, Duration.ONE_MINUTE);
                } catch (NoSuchElementException e2) {
                    address = Iterables.getFirst(addresses, null);
                    LOG.warn("Could not resolve reachable address for "+node+"; falling back to first address "+address, e2);
                } finally {
                    executor.shutdownNow();
                }
            } else {
                LOG.debug("Could not resolve reported address '"+address+"' for "+vmHostname+" ("+setup.getDescription()+"/"+node+"), requesting reachable socket address");
                if (computeService==null) throw Exceptions.propagate(e);
                // this has sometimes already been done in waitForReachable (unless skipped) but easy enough to do again
                address = JcloudsUtil.getFirstReachableAddress(computeService.getContext(), node);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("creating JcloudsPortforwardingSubnetMachineLocation representation for {}@{} ({}/{}) for {}/{}",
                    new Object[] {
                            user,
                            address,
                            Entities.sanitize(sshConfig),
                            sshHostAndPort,
                            setup.getDescription(),
                            node
                    });

        if (isManaged()) {
            // TODO Am adding all `setup` configuration to machine, so that custom values passed in obtain(map) 
            //      are also available to the machine. For example, SshMachineLocation.NO_STDOUT_LOGGING, 
            //      JcloudsLocation.WAIT_FOR_SSHABLE, etc.
            //      This is not being done in super.createJcloudsSshMachineLocation - why not?!
            //      Is there a better way to just pass in the custom, so all other config can just be inherited 
            //      from parent rather than duplicated?
            // TODO Why pass in "config"? I (Aled) am dubious that has any effect!
            // TODO These things need fixed in JcloudsLocation.createJcloudsSshMachineLocation, rather than just here.
            return getManagementContext().getLocationManager().createLocation(LocationSpec.create(JcloudsPortforwardingSubnetMachineLocation.class)
                    .displayName(vmHostname)
                    .configure(setup.getAllConfig())
                    .configure("address", address)
                    .configure("port", sshHostAndPort.isPresent() ? sshHostAndPort.get().getPort() : node.getLoginPort())
                    .configure("user", user)
                    // don't think "config" does anything
                    .configure(sshConfig)
                    // FIXME remove "config" -- inserted directly, above
                    .configure("config", sshConfig)
                    .configure("jcloudsParent", this)
                    .configure("node", node)
                    .configureIfNotNull(CLOUD_AVAILABILITY_ZONE_ID, nodeAvailabilityZone)
                    .configureIfNotNull(CLOUD_REGION_ID, nodeRegion)
                    .configure(JcloudsPortforwardingSubnetMachineLocation.PORT_FORWARDER, getRequiredConfig(PORT_FORWARDER))
                    .configure(CALLER_CONTEXT, setup.get(CALLER_CONTEXT)));
        } else {
            throw new UnsupportedOperationException("Location "+this+" must be managed when obtaining child-locations");
        }
    }
}