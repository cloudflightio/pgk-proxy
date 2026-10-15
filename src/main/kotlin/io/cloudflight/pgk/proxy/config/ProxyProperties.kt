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

package io.cloudflight.pgk.proxy.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "pgk")
data class ProxyProperties(
    val fhir: FhirProperties = FhirProperties(),
    val federation: FederationProperties = FederationProperties(),
    val roleCatalog: RoleCatalogProperties = RoleCatalogProperties(),
    val matrix: MatrixProperties = MatrixProperties(),
    val idp: IdpProperties = IdpProperties()
) {
    data class MatrixProperties(
        val baseUrl: String = "https://matrix.traefik.me"
    )
    data class FhirProperties(
        val baseUrl: String = "http://localhost:8080/fhir",
        val auth: AuthProperties = AuthProperties(),
        val searchMode: SearchMode = SearchMode.REVINCLUDE,
        val logCalls: Boolean = false
    ) {
        enum class SearchMode { REVINCLUDE, FORWARD }

        data class AuthProperties(
            val tokenExchangeUrl: String = "",
            val clientId: String = "",
            val clientSecret: String = "",
            val username: String = "",
            val password: String = ""
        )
    }

    data class FederationProperties(
        val refreshIntervalMinutes: Long = 60,
        // Target for forwarding an accepted federation invite - the local Synapse, reached
        // directly (past any ingress), so the forwarded copy is never re-intercepted.
        val forwardBaseUrl: String = "",

        val domain: String = "https://matrix.traefik.me"
    ) {
        val matrixDomain: String
            get() = domain.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    data class RoleCatalogProperties(
        val localFolder: String = "classpath:valuesets/roles/"
    )

    data class IdpProperties(
        val webhookSecret: String = "",
        val identifierSystem: String = "https://idp.traefik.me/realms/pgk",
        val practitionerProfile: String =
            "http://hl7.at/fhir/HL7ATCoreProfiles/4.0.1/StructureDefinition/at-core-practitioner",
        val telecomSystem: String = "url"
    )
}
