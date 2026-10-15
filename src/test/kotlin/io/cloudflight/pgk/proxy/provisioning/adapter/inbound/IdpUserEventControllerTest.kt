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
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUserOperation
import io.cloudflight.pgk.proxy.search.port.DisplayNameQueryPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class IdpUserEventControllerTest {

    private val secret = "pgk-dev-webhook-secret"
    private val idpUserId = "1b7d4a4e-0000-4000-8000-000000000001"

    private val provisioningService = mockk<IdpProvisioningService>(relaxed = true)
    private val displayNameQuery = mockk<DisplayNameQueryPort>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val properties = ProxyProperties(idp = ProxyProperties.IdpProperties(webhookSecret = secret))
        mockMvc = MockMvcBuilders
            .standaloneSetup(IdpUserEventController(provisioningService, displayNameQuery, properties, AuditLogger()))
            .setMessageConverters(JacksonJsonHttpMessageConverter())
            .build()
    }

    private fun userEvent(
        operation: String = "CREATE",
        userId: String = idpUserId,
        mxid: String? = "max"
    ): String = """
        {
          "operation": "$operation",
          "userId": "$userId",
          "username": "max",
          "firstName": "Max",
          "lastName": "Mustermann",
          "mxid": ${mxid?.let { "\"$it\"" }}
        }
    """.trimIndent()

    @Test
    @DisplayName("A user event of the IDP provisions the user and is accepted")
    fun acceptsUserEvent() {
        val user = slot<IdpUser>()
        val operation = slot<IdpUserOperation>()

        mockMvc.perform(
            post("/internal/idp/user-events")
                .contentType("application/json")
                .content(userEvent())
        ).andExpect(status().isAccepted)

        verify { provisioningService.apply(capture(user), capture(operation)) }
        assertEquals(IdpUserOperation.CREATE, operation.captured)
        assertEquals(idpUserId, user.captured.userId)
        assertEquals("max", user.captured.username)
        assertEquals("Max", user.captured.firstName)
        assertEquals("Mustermann", user.captured.lastName)
        assertEquals(Mxid("@max:matrix.traefik.me"), user.captured.mxid)
    }

    @Test
    @DisplayName("A delete event carries nothing but the operation and the user id")
    fun acceptsDeleteEvent() {
        val user = slot<IdpUser>()
        val operation = slot<IdpUserOperation>()

        mockMvc.perform(
            post("/internal/idp/user-events")
                .contentType("application/json")
                .content("""{"operation":"DELETE","userId":"$idpUserId"}""")
        ).andExpect(status().isAccepted)

        verify { provisioningService.apply(capture(user), capture(operation)) }
        assertEquals(IdpUserOperation.DELETE, operation.captured)
        assertEquals(null, user.captured.mxid)
    }

    @Test
    @DisplayName("An unknown operation is rejected as a bad request")
    fun rejectsUnknownOperation() {
        mockMvc.perform(
            post("/internal/idp/user-events")
                .contentType("application/json")
                .content(userEvent(operation = "PATCH"))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { provisioningService.apply(any(), any()) }
    }

    @Test
    @DisplayName("An event without a user id is rejected as a bad request")
    fun rejectsEventWithoutUserId() {
        mockMvc.perform(
            post("/internal/idp/user-events")
                .contentType("application/json")
                .content(userEvent(userId = ""))
        ).andExpect(status().isBadRequest)

        verify(exactly = 0) { provisioningService.apply(any(), any()) }
    }

    @Test
    @DisplayName("An mxid that cannot be mapped to a practitioner is reported as unprocessable")
    fun rejectsUnusableMxid() {
        every { provisioningService.apply(any(), any()) } throws IllegalArgumentException("cannot map mxid")

        mockMvc.perform(
            post("/internal/idp/user-events")
                .contentType("application/json")
                .content(userEvent(mxid = "a".repeat(70)))
        ).andExpect(status().`is`(400))
    }

    @Test
    @DisplayName("The chat display names are exposed per full Matrix user id")
    fun exposesDisplayNames() {
        every { displayNameQuery.queryDisplayNames() } returns mapOf(
            Mxid("@max:matrix.traefik.me") to "Mustermann, Max (AKH Wien)"
        )

        mockMvc.perform(get("/internal/idp/display-names"))
            .andExpect(status().isOk)
            .andExpect(
                content().json("""{"@max:matrix.traefik.me":"Mustermann, Max (AKH Wien)"}""")
            )
    }
}
