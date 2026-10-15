/*
 * Copyright 2026 MA 01 - Wien Digital
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.cloudflight.pgk.proxy.federation.adapter.outbound

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.TokenClientParam
import io.cloudflight.pgk.proxy.federation.domain.FederationEndpoint
import io.cloudflight.pgk.proxy.federation.mapper.FederationEndpointMapper
import io.cloudflight.pgk.proxy.federation.port.FederationListPort
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Endpoint
import org.springframework.stereotype.Component

@Component
class HapiFhirFederationEndpointAdapter(
    private val fhirClient: IGenericClient,
    private val endpointMapper: FederationEndpointMapper
) : FederationListPort {

    override fun fetchFederationEndpoints(): List<FederationEndpoint> {
        val bundle = fhirClient.search<Bundle>()
            .forResource(Endpoint::class.java)
            .where(TokenClientParam(Endpoint.SP_CONNECTION_TYPE).exactly()
                .systemAndIdentifier(CONNECTION_TYPE_SYSTEM, CONNECTION_TYPE_CODE))
            .returnBundle(Bundle::class.java)
            .execute()

        return bundle.entry.mapNotNull { it.resource as? Endpoint }
            .mapNotNull(endpointMapper::toDomain)
            .distinctBy { it.fqdn }
    }

    companion object {
        const val CONNECTION_TYPE_SYSTEM = "https://pgk.elga.gv.at/CodeSystem/endpoint-connection-type"
        const val CONNECTION_TYPE_CODE = "matrix-homeserver"
    }
}
