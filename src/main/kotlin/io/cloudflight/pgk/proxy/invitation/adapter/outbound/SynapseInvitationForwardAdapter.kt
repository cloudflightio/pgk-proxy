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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.invitation.mapper.InvitationRequestMapper
import io.cloudflight.pgk.proxy.invitation.port.InvitationForwardPort
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardFailedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.toEntity
import java.net.URI

/**
 * Transparent pass-through of an intercepted federation invite. The accepted invite is forwarded
 * to the local Synapse at `pgk.federation.forward-base-url` - directly, without round-tripping
 * through an ingress, so the copy can never be re-intercepted (and no ingress header can be forged
 * to skip the policy check). Forwarding results are reported through [InvitationForwardedEvent] /
 * [InvitationForwardFailedEvent] for the audit log.
 *
 * The original request URI is forwarded verbatim (no re-encoding of the room/event path segments):
 * the Matrix federation request signature covers the exact URI the origin server signed, so any
 * normalization here would break signature verification on the receiving Synapse.
 */
@Component
class SynapseInvitationForwardAdapter(
    private val restClient: RestClient,
    private val applicationEvents: ApplicationEventPublisher,
    private val requestMapper: InvitationRequestMapper,
    private val properties: ProxyProperties
) : InvitationForwardPort {

    override fun forwardInvite(
        roomId: String,
        eventId: String,
        originalUri: String,
        authorization: String?,
        body: String,
    ): ResponseEntity<String> {
        val destination = try {
            requestMapper.fromPdu(body).recipient.domain
            buildDestination(originalUri)
        } catch (e: IllegalArgumentException) {
            applicationEvents.publishEvent(
                InvitationForwardFailedEvent(roomId, eventId, "unknown", "invite PDU has no recipient")
            )
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
        return try {
            val request = restClient.put()
                .uri(URI.create(destination))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)

            if (authorization != null) {
                request.header(HttpHeaders.AUTHORIZATION, authorization)
            }

            request.retrieve().toEntity<String>().also { response ->
                if (response.statusCode.is2xxSuccessful) {
                    applicationEvents.publishEvent(
                        InvitationForwardedEvent(roomId, eventId, destination, response.statusCode.value())
                    )
                } else {
                    applicationEvents.publishEvent(
                        InvitationForwardFailedEvent(roomId, eventId, destination, response.statusCode.toString())
                    )
                }
            }
        } catch (e: RestClientException) {
            applicationEvents.publishEvent(
                InvitationForwardFailedEvent(roomId, eventId, destination, e.message)
            )
            ResponseEntity.internalServerError().build()
        }
    }

    private fun buildDestination(originalUri: String): String =
        properties.federation.forwardBaseUrl.trimEnd('/') + originalUri
}
