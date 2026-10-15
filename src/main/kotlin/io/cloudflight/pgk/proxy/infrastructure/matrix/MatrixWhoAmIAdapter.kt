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

package io.cloudflight.pgk.proxy.infrastructure.matrix

import com.fasterxml.jackson.annotation.JsonProperty
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * Resolves an access token to a user via the Matrix whoami endpoint.
 */
@Component
class MatrixWhoAmIAdapter(
    private val restClient: RestClient,
    private val properties: ProxyProperties,
    private val auditLogger: AuditLogger
) : WhoAmIPort {

    /** @return the mxid belonging to [accessToken], throwing on failure */
    override fun whoami(accessToken: String): Mxid {
        return runCatching {
            restClient.get()
                .uri("${properties.matrix.baseUrl}/_matrix/client/v3/account/whoami")
                .headers { it.setBearerAuth(accessToken) }
                .retrieve()
                .body<WhoAmIResponse>()!!
        }.onFailure {
            auditLogger.error(
                "system", AuditAction.MatrixWhoamiFailed,
                "whoami failed for provided access token: ${it.message}", it
            )
        }.getOrThrow()
            .userId
            .let { Mxid(it) }
    }

    data class WhoAmIResponse(
        @JsonProperty("user_id") val userId: String
    )
}
