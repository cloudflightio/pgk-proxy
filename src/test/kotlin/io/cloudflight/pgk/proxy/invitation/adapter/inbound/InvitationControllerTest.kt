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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class InvitationControllerTest {

    private val invitationControlService = mock(InvitationControlService::class.java)
    private val invitationForwardPort = mock(InvitationForwardPort::class.java)
    private lateinit var mockMvc: MockMvc

    private val roomId = "testroom"
    private val eventId = "inviteevent"
    private val originalUri = "/_matrix/federation/v2/invite/$roomId/$eventId"
    private val body = """{"origin":"matrix.pgk.example","event":{"type":"m.room.member"}}"""
    private val authorization = "Bearer s3cret"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(InvitationController(invitationControlService, invitationForwardPort))
            .build()
    }

    @Test
    @DisplayName("returns 200 and forwards when the invitation is allowed")
    fun returns200AndForwardsWhenAllowed() {
        whenever(invitationControlService.evaluateInvitation(body))
            .thenReturn(InvitationEvaluation.Allowed)
        whenever(invitationForwardPort.forwardInvite(roomId, eventId, originalUri, authorization, body))
            .thenReturn(ResponseEntity.ok("forwarded"))

        mockMvc.perform(put("/_matrix/federation/v2/invite/$roomId/$eventId")
            .header("Authorization", authorization)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isOk)

        verify(invitationControlService).evaluateInvitation(body)
        verify(invitationForwardPort).forwardInvite(roomId, eventId, originalUri, authorization, body)
    }

    @Test
    @DisplayName("returns the result of forwarding when allowed")
    fun returnsForwardingResultWhenAllowed() {
        whenever(invitationControlService.evaluateInvitation(body))
            .thenReturn(InvitationEvaluation.Allowed)
        whenever(invitationForwardPort.forwardInvite(roomId, eventId, originalUri, authorization, body))
            .thenReturn(ResponseEntity.status(500).body("boom"))

        mockMvc.perform(put("/_matrix/federation/v2/invite/$roomId/$eventId")
            .header("Authorization", authorization)
            .contentType("application/json")
            .content(body))
            .andExpect(status().isInternalServerError)
    }

    @Test
    @DisplayName("returns 403 with the policy message and does not forward when denied by federation policy")
    fun returns403AndDoesNotForwardWhenDeniedByFederationPolicy() {
        whenever(invitationControlService.evaluateInvitation(body))
            .thenReturn(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY))

        mockMvc.perform(put("/_matrix/federation/v2/invite/$roomId/$eventId")
            .contentType("application/json")
            .content(body))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"errcode": "M_FORBIDDEN", "error": "Invite not allowed by policy"}"""))

        verify(invitationForwardPort, never()).forwardInvite(any(), any(), any(), any(), any())
    }

    @Test
    @DisplayName("returns 403 with the blacklist message and does not forward when denied by a personal blacklist rule")
    fun returns403AndDoesNotForwardWhenDeniedByPersonalBlacklist() {
        whenever(invitationControlService.evaluateInvitation(body))
            .thenReturn(InvitationEvaluation.Denied(DenialReason.PERSONAL_BLACKLIST))

        mockMvc.perform(put("/_matrix/federation/v2/invite/$roomId/$eventId")
            .contentType("application/json")
            .content(body))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"errcode": "M_FORBIDDEN", "error": "Invitation blocked by user policy."}"""))

        verify(invitationForwardPort, never()).forwardInvite(any(), any(), any(), any(), any())
    }
}
