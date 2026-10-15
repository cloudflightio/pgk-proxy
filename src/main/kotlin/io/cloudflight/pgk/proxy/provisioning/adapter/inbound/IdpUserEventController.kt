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

package io.cloudflight.pgk.proxy.provisioning.adapter.inbound

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.provisioning.application.IdpProvisioningService
import io.cloudflight.pgk.proxy.provisioning.application.IdpUserEventRequest
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.search.port.DisplayNameQueryPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Receives user lifecycle events from the Keycloak provider. Access to `/internal/idp/\*\*` is
 * protected by HTTP Basic auth (see [io.cloudflight.pgk.proxy.config.SecurityConfig]).
 */
@RestController
@RequestMapping("/internal/idp")
class IdpUserEventController(
    private val provisioningService: IdpProvisioningService,
    private val displayNameQuery: DisplayNameQueryPort,
    private val properties: ProxyProperties,
    private val auditLogger: AuditLogger
) {

    /**
     * Chat display names for every practitioner with a Matrix id
     */
    @GetMapping("/display-names")
    fun displayNames(): Map<String, String> =
        displayNameQuery.queryDisplayNames().entries.associate { (mxid, name) -> mxid.value to name }

    @PostMapping("/user-events")
    fun onUserEvent(
        @RequestBody request: IdpUserEventRequest
    ): ResponseEntity<Void> {
        if (request.userId.isBlank()) {
            return ResponseEntity.badRequest().build()
        }

        return try {
            provisioningService.apply(request.toIdpUser(), request.operation)
            ResponseEntity.accepted().build()
        } catch (e: IllegalArgumentException) {
            auditLogger.warn(
                "system", AuditAction.IdpUserEventRejected,
                "Rejected IDP user event for user ${request.userId}: ${e.message}"
            )
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
    }

    private fun IdpUserEventRequest.toIdpUser(): IdpUser = IdpUser(
        userId = userId,
        username = username?.ifBlank { null },
        firstName = firstName?.ifBlank { null },
        lastName = lastName?.ifBlank { null },
        mxid = mxid?.ifBlank { null }?.let(::toMxid)
    )

    private fun toMxid(localpart: String): Mxid {
        require(localpart.matches(MXID_LOCALPART_PATTERN)) { "Invalid Matrix user localpart" }
        return Mxid("@$localpart:${properties.federation.matrixDomain}")
    }

    companion object {
        private val MXID_LOCALPART_PATTERN = Regex("^[a-z0-9._=\\-/]+$")
    }
}
