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

import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.shared.events.RoleRemovalFailedEvent
import io.cloudflight.pgk.proxy.ui.application.PractitionerRoleAssignmentService
import io.cloudflight.pgk.proxy.ui.application.PractitionerRoleRow
import io.cloudflight.pgk.proxy.ui.application.RoleAssignmentOption
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.ViewResolver
import org.springframework.web.servlet.view.AbstractView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant

class PractitionerRolesControllerTest {

    private val service = org.mockito.Mockito.mock(PractitionerRoleAssignmentService::class.java)
    private val eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(PractitionerRolesController(service, eventPublisher))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setViewResolvers(noOpViewResolver())
            .build()
        SecurityContextHolder.clearContext()
    }

    /**
     * Standalone MockMvc has no Thymeleaf resolver; without one, the plain page view name
     * `practitioner-roles` falls through to the default internal-resource resolver and trips
     * the "circular view path" guard. A no-op resolver keeps these tests focused on the
     * controller (status/model/view name) without rendering templates.
     */
    private fun noOpViewResolver(): ViewResolver = ViewResolver { _, _ ->
        object : AbstractView() {
            override fun renderMergedOutputModel(
                model: MutableMap<String, Any>,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                response.contentType = "text/html"
            }
        }
    }

    private fun authenticateAs(subject: String) {
        val oidcUser: OidcUser = DefaultOidcUser(
            emptyList(),
            OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), mapOf("sub" to subject)),
            OidcUserInfo(mapOf("sub" to subject))
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(oidcUser, "n/a", emptyList())
    }

    // --- Page ---------------------------------------------------------------

    @Test
    @DisplayName("Page renders the practitioner rows")
    fun pageRendersRows() {
        authenticateAs("user-1")
        whenever(service.listPractitioners("")).thenReturn(
            listOf(
                PractitionerRoleRow(
                    "1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB",
                    listOf(PractitionerRoleRow.RoleChip("Fachärztin/Facharzt für Chirurgie", "https://cs|106"))
                )
            )
        )

        mockMvc.perform(get("/admin/practitioner-roles"))
            .andExpect(status().isOk)
            .andExpect(view().name("practitioner-roles"))
            .andReturn()

        verify(service).listPractitioners("")
    }

    // --- Search -------------------------------------------------------------

    @Test
    @DisplayName("Search returns the table fragment filtered by name")
    fun searchFiltersTable() {
        whenever(service.listPractitioners("anna")).thenReturn(
            listOf(PractitionerRoleRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", emptyList()))
        )

        mockMvc.perform(get("/admin/practitioner-roles/search")
            .param("q", "anna"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: practitioner-table"))
            .andReturn()

        verify(service).listPractitioners("anna")
    }

    // --- Organization combobox ----------------------------------------------

    @Test
    @DisplayName("Organization search returns matching organizations as suggestions")
    fun searchOrganizationsReturnsSuggestions() {
        whenever(service.searchOrganizations("Kardio"))
            .thenReturn(listOf(OrganizationOption("o-1", "Klinik Favoriten – Abteilung Kardiochirurgie")))

        mockMvc.perform(get("/admin/practitioner-roles/organizations/search").param("organizationQuery", "Kardio"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: organizationList"))
            .andExpect(model().attribute("q", "Kardio"))
            .andExpect(model().attribute<List<OrganizationOption>>("items", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()
    }

    @Test
    @DisplayName("Organization select sets the dialog org box")
    fun selectOrganizationSetsBox() {
        mockMvc.perform(get("/admin/practitioner-roles/organizations/select")
            .param("id", "o-1")
            .param("name", "Klinik Favoriten – Abteilung Kardiochirurgie"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: organizationBox"))
            .andExpect(model().attribute("organizationId", "o-1"))
            .andReturn()
    }

    // --- Dialog -------------------------------------------------------------

    @Test
    @DisplayName("Dialog renders the practitioner's title, organization and role list")
    fun dialogRendersRoles() {
        val practitioner = PractitionerRoleRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", emptyList())
        whenever(service.findPractitioner("1")).thenReturn(practitioner)
        whenever(service.findPrimaryOrganization("1"))
            .thenReturn(OrganizationOption("o-1", "Klinik Favoriten – Abteilung Kardiochirurgie"))
        whenever(service.roleOptions("1", "", null)).thenReturn(
            listOf(
                RoleAssignmentOption("https://cs|106", "Fachärztin/Facharzt für Chirurgie", checked = true)
            )
        )

        mockMvc.perform(get("/admin/practitioner-roles/1/dialog"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: dialog"))
            .andExpect(model().attribute("dialogTitle", "Rollen zuweisen – Dr. Anna Berger"))
            .andExpect(model().attribute("organizationId", "o-1"))
            .andExpect(model().attribute<List<RoleAssignmentOption>>("roles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(service).roleOptions("1", "", null)
    }

    @Test
    @DisplayName("Dialog with a query returns only the role-list fragment and keeps the checked state")
    fun dialogFiltersRolesByQuery() {
        whenever(service.findPractitioner("1")).thenReturn(
            PractitionerRoleRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", emptyList())
        )
        whenever(service.findPrimaryOrganization("1")).thenReturn(OrganizationOption("o-1", "Kardio"))
        whenever(service.roleOptions("1", "chir", setOf("https://cs|106"))).thenReturn(emptyList())

        mockMvc.perform(get("/admin/practitioner-roles/1/dialog")
            .param("q", "chir")
            .param("roleTokens", "https://cs|106"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: role-list"))
            .andExpect(model().attribute("q", "chir"))
            .andReturn()

        verify(service).roleOptions("1", "chir", setOf("https://cs|106"))
    }

    @Test
    @DisplayName("Dialog rejects an unknown practitioner")
    fun dialogRejectsUnknownPractitioner() {
        whenever(service.findPractitioner("unknown")).thenReturn(null)

        mockMvc.perform(get("/admin/practitioner-roles/unknown/dialog"))
            .andExpect(status().isNotFound)
            .andReturn()
    }

    // --- Save ---------------------------------------------------------------

    @Test
    @DisplayName("Save applies the checked state and refreshes the table")
    fun saveAppliesAssignment() {
        whenever(service.applyAssignment("1", "o-1", listOf("https://cs|106", "https://cs|212")))
            .thenReturn("Rollen erfolgreich zugewiesen")
        whenever(service.listPractitioners("")).thenReturn(emptyList())

        mockMvc.perform(post("/admin/practitioner-roles/1")
            .param("organizationId", "o-1")
            .param("roleTokens", "https://cs|106", "https://cs|212"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: save-ok"))
            .andExpect(model().attribute("message", "Rollen erfolgreich zugewiesen"))
            .andReturn()

        verify(service).applyAssignment("1", "o-1", listOf("https://cs|106", "https://cs|212"))
        verify(service).listPractitioners("")
    }

    @Test
    @DisplayName("Save without changes reports a no-op and still refreshes the table")
    fun saveWithoutChangesIsANoOp() {
        whenever(service.applyAssignment(eq("1"), eq("o-1"), any())).thenReturn(null)
        whenever(service.listPractitioners("")).thenReturn(emptyList())

        mockMvc.perform(post("/admin/practitioner-roles/1")
            .param("organizationId", "o-1"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: save-ok"))
            .andExpect(model().attribute("message", "Keine Änderungen vorgenommen"))
            .andReturn()
    }

    @Test
    @DisplayName("Failed save returns the inline error fragment")
    fun failedSaveReturnsInlineError() {
        whenever(service.applyAssignment(eq("1"), eq("o-1"), any()))
            .thenThrow(IllegalArgumentException("Organisation muss ausgewählt sein"))

        mockMvc.perform(post("/admin/practitioner-roles/1")
            .param("organizationId", "o-1"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: save-error"))
            .andExpect(model().attribute("error", "Organisation muss ausgewählt sein"))
            .andReturn()
    }

    // --- Quick remove -------------------------------------------------------

    @Test
    @DisplayName("Quick remove deletes the role and refreshes the table")
    fun removeRoleRefreshesTable() {
        whenever(service.removeRole("1", "https://cs|106")).thenReturn(true)
        whenever(service.listPractitioners("")).thenReturn(emptyList())

        mockMvc.perform(post("/admin/practitioner-roles/1/remove-role")
            .param("roleToken", "https://cs|106"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: remove-ok"))
            .andExpect(model().attribute("message", "Rolle entfernt"))
            .andReturn()

        verify(service).removeRole("1", "https://cs|106")
        verify(service).listPractitioners("")
    }

    @Test
    @DisplayName("Quick remove reports the error fragment when the role could not be removed")
    fun removeRoleFails() {
        whenever(service.removeRole(eq("1"), eq("https://cs|106")))
            .thenThrow(IllegalArgumentException("Rolle war nicht zugewiesen"))
        whenever(service.listPractitioners("")).thenReturn(emptyList())

        mockMvc.perform(post("/admin/practitioner-roles/1/remove-role")
            .param("roleToken", "https://cs|106"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/practitioner-roles :: remove-error"))
            .andReturn()

        val captured = org.mockito.kotlin.argumentCaptor<RoleRemovalFailedEvent>()
        verify(eventPublisher).publishEvent(captured.capture())
        org.junit.jupiter.api.Assertions.assertEquals("1", captured.firstValue.practitionerId)
        org.junit.jupiter.api.Assertions.assertEquals("https://cs|106", captured.firstValue.roleToken)
    }
}
