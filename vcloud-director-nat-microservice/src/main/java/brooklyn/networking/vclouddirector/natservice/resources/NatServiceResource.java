/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.networking.vclouddirector.natservice.resources;

import java.util.List;

import javax.ws.rs.core.Response;

import brooklyn.networking.vclouddirector.PortForwardingConfig;
import brooklyn.networking.vclouddirector.natservice.api.NatServiceApi;
import brooklyn.networking.vclouddirector.natservice.domain.NatRuleSummary;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.net.Protocol;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.net.HostAndPort;
import com.vmware.vcloud.api.rest.schema.NatRuleType;
import com.vmware.vcloud.sdk.VCloudException;

public class NatServiceResource extends AbstractRestResource implements NatServiceApi {

    @Override
    public List<NatRuleSummary> list(String endpoint, String identity, String credential) {
        try {
            List<NatRuleType> rules = dispatcher().getNatRules(endpoint, identity, credential);
            return FluentIterable
                    .from(rules)
                    .transform(new Function<NatRuleType, NatRuleSummary>() {
                        @Override
                        public NatRuleSummary apply(NatRuleType input) {
                            return NatRuleSummary.from(input);
                        }
                    })
                    .toList();
        } catch (VCloudException e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public Response openPortForwarding(String endpoint,
            String identity, String credential, String protocol,
            String original, String translated) {
        HostAndPort originalHostAndPort = HostAndPort.fromString(original);
        HostAndPort translatedHostAndPort = HostAndPort.fromString(translated);
        try {
            dispatcher().openPortForwarding(endpoint, identity, credential, new PortForwardingConfig()
                    .protocol(Protocol.valueOf(protocol.toUpperCase()))
                    .publicIp(originalHostAndPort.getHostText())
                    .publicPort(originalHostAndPort.getPort())
                    .target(translatedHostAndPort));
            
            return Response.status(Response.Status.OK).build();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

    @Override
    public Response closePortForwarding(String endpoint,
            String identity, String credential, String protocol,
            String original, String translated) {
        // TODO throw 404 if not found
        HostAndPort originalHostAndPort = HostAndPort.fromString(original);
        HostAndPort translatedHostAndPort = HostAndPort.fromString(translated);
        try {
            dispatcher().closePortForwarding(endpoint, identity, credential, new PortForwardingConfig()
                    .protocol(Protocol.valueOf(protocol.toUpperCase()))
                    .publicIp(originalHostAndPort.getHostText())
                    .publicPort(originalHostAndPort.getPort())
                    .target(translatedHostAndPort));
            
            return Response.status(Response.Status.OK).build();
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }

}
