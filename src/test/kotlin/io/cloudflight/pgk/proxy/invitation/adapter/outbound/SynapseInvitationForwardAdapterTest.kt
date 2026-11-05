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
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardFailedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class SynapseInvitationForwardAdapterTest {

    private val applicationEvents: ApplicationEventPublisher = mock()

    private val inviteBody =
        """{"origin":"matrix.traefik.me","event":{"sender":"@alice:matrix.traefik.me","state_key":"@bob:matrix.traefik.me"}}"""

    private fun adapterWithServer(
        outgoingUrlMapper: OutgoingUrlMapper = DefaultOutgoingUrlMapper()
    ): Pair<SynapseInvitationForwardAdapter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val properties =
            ProxyProperties(federation = ProxyProperties.FederationProperties(forwardBaseUrl = "http://synapse:8008"))
        val adapter =
            SynapseInvitationForwardAdapter(builder.build(), applicationEvents, outgoingUrlMapper, properties)
        return adapter to server
    }

    @Test
    @DisplayName("forwards the invite to the configured forward base URL")
    fun forwardsInviteToForwardBaseUrl() {
        val (adapter, server) = adapterWithServer()

        server.expect(requestTo("http://synapse:8008/_matrix/federation/v2/invite/room1/evt1"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer s3cret"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(inviteBody))
            .andRespond(withSuccess("""{"event":{},"origin":"matrix.traefik.me"}""", MediaType.APPLICATION_JSON))

        val response = adapter.forwardInvite(
            "room1",
            "evt1",
            "/_matrix/federation/v2/invite/room1/evt1",
            "matrix.traefik.me",
            "https://matrix.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            "Bearer s3cret",
            inviteBody
        )

        assertEquals(200, response.statusCode.value())
        server.verify()
        verify(applicationEvents).publishEvent(
            argWhere<InvitationForwardedEvent> {
                it.roomId == "room1" && it.eventId == "evt1" && it.statusCode == 200
            }
        )
    }

    @Test
    @DisplayName("forwards the original request URI verbatim so the federation signature is preserved")
    fun forwardsOriginalUriVerbatim() {
        val (adapter, server) = adapterWithServer()

        val originalUri = "/_matrix/federation/v2/invite/!room%3Aexample.com/%24evt%3F1"
        server.expect(requestTo("http://synapse:8008$originalUri"))
            .andRespond(withSuccess("""{"event":{},"origin":"matrix.traefik.me"}""", MediaType.APPLICATION_JSON))

        val response = adapter.forwardInvite(
            "!room:example.com",
            "evt?1",
            originalUri,
            "matrix.traefik.me",
            "https://matrix.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            "Bearer s3cret",
            inviteBody
        )

        assertEquals(200, response.statusCode.value())
        server.verify()
    }

    @Test
    @DisplayName("returns 500 and publishes a failure event when forwarding fails")
    fun returns500AndPublishesFailureWhenSynapseUnreachable() {
        val (adapter, server) = adapterWithServer()

        server.expect(requestTo("http://synapse:8008/_matrix/federation/v2/invite/room1/evt1"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withServerError())

        val response = adapter.forwardInvite(
            "room1",
            "evt1",
            "/_matrix/federation/v2/invite/room1/evt1",
            "matrix.traefik.me",
            "https://matrix.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            "Bearer s3cret",
            inviteBody
        )

        assertEquals(500, response.statusCode.value())
        server.verify()
        verify(applicationEvents).publishEvent(any<InvitationForwardFailedEvent>())
    }

    @Test
    @DisplayName("forwards an outgoing invite to the URL returned by the outgoing URL mapper")
    fun forwardsOutgoingInviteToMappedOutgoingUrl() {
        val (adapter, server) = adapterWithServer()

        server.expect(requestTo("https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().string(inviteBody))
            .andRespond(withSuccess("""{"event":{},"origin":"matrix.traefik.me"}""", MediaType.APPLICATION_JSON))

        val response = adapter.forwardInvite(
            "room1",
            "evt1",
            "/_matrix/federation/v2/invite/room1/evt1",
            "partner.traefik.me",
            "https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            null,
            inviteBody
        )

        assertEquals(200, response.statusCode.value())
        server.verify()
        verify(applicationEvents).publishEvent(
            argWhere<InvitationForwardedEvent> {
                it.roomId == "room1" && it.eventId == "evt1" && it.statusCode == 200 &&
                        it.target == "https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"
            }
        )
    }

    @Test
    @DisplayName("applies the local URL mapper to outgoing invites, rewriting http to https")
    fun forwardsOutgoingInviteThroughLocalOutgoingUrlMapper() {
        val (adapter, server) = adapterWithServer(LocalOutgoingUrlMapper())

        server.expect(requestTo("https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("""{"event":{},"origin":"matrix.traefik.me"}""", MediaType.APPLICATION_JSON))

        val response = adapter.forwardInvite(
            "room1",
            "evt1",
            "/_matrix/federation/v2/invite/room1/evt1",
            "partner.traefik.me",
            "http://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            null,
            inviteBody
        )

        assertEquals(200, response.statusCode.value())
        server.verify()
        verify(applicationEvents).publishEvent(
            argWhere<InvitationForwardedEvent> {
                it.target == "https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"
            }
        )
    }

    @Test
    @DisplayName("reports a failure for an outgoing invite against the mapped outgoing URL")
    fun returns500AndPublishesFailureForOutgoingInvite() {
        val (adapter, server) = adapterWithServer()

        server.expect(requestTo("https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withServerError())

        val response = adapter.forwardInvite(
            "room1",
            "evt1",
            "/_matrix/federation/v2/invite/room1/evt1",
            "partner.traefik.me",
            "https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1",
            null,
            inviteBody
        )

        assertEquals(500, response.statusCode.value())
        server.verify()
        verify(applicationEvents).publishEvent(
            argWhere<InvitationForwardFailedEvent> {
                it.target == "https://partner.traefik.me/_matrix/federation/v2/invite/room1/evt1"
            }
        )
    }
}
