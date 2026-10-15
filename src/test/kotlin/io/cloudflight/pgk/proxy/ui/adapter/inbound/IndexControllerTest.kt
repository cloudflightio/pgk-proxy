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

package io.cloudflight.pgk.proxy.ui.adapter.inbound

import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.CreateRoleRequest
import io.cloudflight.pgk.proxy.ui.application.RoleManagementService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class IndexControllerTest {

    private val roleManagement = org.mockito.Mockito.mock(RoleManagementService::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(IndexController(roleManagement, AuditLogger()))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        SecurityContextHolder.clearContext()
    }

    private fun authenticateAs(subject: String) {
        val oidcUser: OidcUser = DefaultOidcUser(
            emptyList(),
            OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), mapOf("sub" to subject, "preferred_username" to "max")),
            OidcUserInfo(mapOf("sub" to subject))
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(oidcUser, "n/a", emptyList())
    }

    private fun ownPractitionerReturns(id: String) {
        whenever(roleManagement.findPractitionerByUserId("user-1"))
            .thenReturn(
                io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption(
                    id = id, name = "Max Mustermann", trustLevel = ""
                )
            )
    }

    // --- Create role ----------------------------------------------------------

    @Test
    fun `create role adds a role and returns success`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("message", "Roles successfully added"))
            .andReturn()

        verify(roleManagement).applyRoleChanges(
            CreateRoleRequest(
                practitionerId = "Practitioner/1",
                organizationId = "o1",
                roleIds = listOf("https://pgk/cs|HAP"),
                removedRoleIds = emptyList()
            )
        )
    }

    @Test
    fun `create role removes roles when only removals are given`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "")
            .param("removedRoleIds", "role-1|https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("message", "Roles successfully removed"))
            .andReturn()

        verify(roleManagement).applyRoleChanges(
            CreateRoleRequest(
                practitionerId = "Practitioner/1",
                organizationId = "o1",
                roleIds = emptyList(),
                removedRoleIds = listOf("role-1|https://pgk/cs|HAP")
            )
        )
    }

    @Test
    fun `create role updates roles when adding and removing`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|INTERN")
            .param("removedRoleIds", "role-1|https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("message", "Roles successfully updated"))
            .andReturn()

        verify(roleManagement).applyRoleChanges(
            CreateRoleRequest(
                practitionerId = "Practitioner/1",
                organizationId = "o1",
                roleIds = listOf("https://pgk/cs|INTERN"),
                removedRoleIds = listOf("role-1|https://pgk/cs|HAP")
            )
        )
    }

    @Test
    fun `create role returns an error fragment when the service rejects the request`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        whenever(roleManagement.applyRoleChanges(any()))
            .thenThrow(IllegalArgumentException("Organisation muss ausgewählt sein"))

        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("error", "Organisation muss ausgewählt sein"))
            .andReturn()
    }

    @Test
    fun `create role returns a generic error fragment on unexpected failure`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        whenever(roleManagement.applyRoleChanges(any()))
            .thenThrow(RuntimeException("boom"))

        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute(
                "error",
                "Failed to apply practitioner role changes"
            ))
            .andReturn()
    }

    @Test
    fun `create role rejects changes to another practitioner's roles`() {
        authenticateAs("user-1")
        ownPractitionerReturns("Practitioner/1")
        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/2")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute(
                "error",
                "Roles can only be changed for your own practitioner"
            ))
            .andReturn()

        verify(roleManagement, never()).applyRoleChanges(any())
    }

    @Test
    fun `create role rejects unauthenticated role changes`() {
        ownPractitionerReturns("Practitioner/1")
        mockMvc.perform(post("/api/roles/create")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("practitionerId", "Practitioner/1")
            .param("organizationId", "o1")
            .param("roleIds", "https://pgk/cs|HAP"))
            .andExpect(status().isOk)
            .andExpect(model().attribute(
                "error",
                "You must be logged in to manage roles"
            ))
            .andReturn()

        verify(roleManagement, never()).applyRoleChanges(any())
    }
}
