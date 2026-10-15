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

package io.cloudflight.pgk.proxy.invitation.adapter.inbound

import io.cloudflight.pgk.proxy.invitation.application.DenialReason
import io.cloudflight.pgk.proxy.invitation.application.InvitationControlService
import io.cloudflight.pgk.proxy.invitation.application.InvitationEvaluation
import io.cloudflight.pgk.proxy.invitation.port.InvitationForwardPort
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/_matrix/federation/v2")
class InvitationController(
    private val invitationControlService: InvitationControlService,
    private val invitationForwardPort: InvitationForwardPort
) {

    @PutMapping(
        "/invite/{roomId}/{eventId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    @RateLimiter(
        name = "@invitePeerKeyResolver.resolve(#a2)",
        configuration = "federationInvite",
        fallbackMethod = "inviteLimitExceeded"
    )
    open fun invitation(
        @PathVariable roomId: String,
        @PathVariable eventId: String,
        @RequestBody body: String,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        request: HttpServletRequest,
    ): ResponseEntity<String> {
        return when (val evaluation = invitationControlService.evaluateInvitation(body)) {
            is InvitationEvaluation.Allowed -> {
                val upstream = invitationForwardPort.forwardInvite(
                    roomId, eventId, request.requestURI, authorization, body
                )
                ResponseEntity.status(upstream.statusCode).contentType(MediaType.APPLICATION_JSON).body(upstream.body)
            }
            is InvitationEvaluation.Denied -> ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(denyBody(evaluation.reason))
        }
    }

    private fun denyBody(reason: DenialReason): String = when (reason) {
        DenialReason.FEDERATION_POLICY -> """{"errcode": "M_FORBIDDEN", "error": "Invite not allowed by policy"}"""
        DenialReason.PERSONAL_BLACKLIST -> """{"errcode": "M_FORBIDDEN", "error": "Invitation blocked by user policy."}"""
        DenialReason.ORG_BLOCKED_USER, DenialReason.ORG_BLOCKED_ROLE, DenialReason.ORG_RIGHTS_DISABLED ->
            """{"errcode": "M_FORBIDDEN", "error": "Invitation blocked by organization policy."}"""
    }

    private fun inviteLimitExceeded(
        roomId: String,
        eventId: String,
        body: String,
        authorization: String?,
        request: HttpServletRequest,
        throwable: Throwable?,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"errcode": "M_LIMIT_EXCEEDED", "error": "Too many requests"}""")
}
