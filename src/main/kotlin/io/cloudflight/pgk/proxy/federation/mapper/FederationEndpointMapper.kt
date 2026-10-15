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

package io.cloudflight.pgk.proxy.federation.mapper

import io.cloudflight.pgk.proxy.federation.domain.FederationEndpoint
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.hl7.fhir.r4.model.Endpoint
import org.springframework.stereotype.Component
import java.net.URI

@Component
class FederationEndpointMapper {

    fun toDomain(endpoint: Endpoint): FederationEndpoint? {
        val trustLevel = TrustLevel.fromMeta(endpoint.meta)
        if (trustLevel < TrustLevel.TL_4) return null

        val fqdn = extractFqdn(endpoint.address) ?: return null
        return FederationEndpoint(fqdn = fqdn, trustLevel = trustLevel)
    }

    private fun extractFqdn(address: String?): Fqdn? {
        if (address.isNullOrBlank()) return null

        val host = if (address.contains("://")) {
            runCatching { URI(address).host }.getOrNull()
        } else {
            address.substringBefore("/").substringBefore(":")
        }

        if (host.isNullOrBlank()) return null
        return runCatching { Fqdn(host) }.getOrNull()

    }
}
