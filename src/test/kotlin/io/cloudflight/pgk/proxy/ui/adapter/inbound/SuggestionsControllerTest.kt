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

import io.cloudflight.pgk.proxy.search.api.domain.model.*
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.RoleManagementService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SuggestionsControllerTest {

    private val roleManagement = org.mockito.Mockito.mock(RoleManagementService::class.java)
    private val roleChipMapper = org.mockito.Mockito.mock(RoleChipMapper::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(SuggestionsController(roleManagement, roleChipMapper, AuditLogger())).build()
    }

    // --- Practitioner search ------------------------------------------------

    @Test
    @DisplayName("Practitioner search endpoint returns the matching practitioners")
    fun practitionerSearchReturnsMatchingPractitioners() {
        whenever(roleManagement.searchPractitioners("al"))
            .thenReturn(listOf(PractitionerOption("Practitioner/1", "Alice Müller", "TL_1")))

        mockMvc.perform(get("/api/practitioners/search").param("q", "al"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("q", "al"))
            .andExpect(model().attribute<List<Any>>("items", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()
    }

    @Test
    @DisplayName("Practitioner search endpoint forwards the query to the service")
    fun practitionerSearchForwardsQueryToService() {
        whenever(roleManagement.searchPractitioners("al")).thenReturn(emptyList())

        mockMvc.perform(get("/api/practitioners/search").param("q", "al"))
            .andExpect(status().isOk)
            .andReturn()

        verify(roleManagement).searchPractitioners("al")
    }

    // --- Organization search ------------------------------------------------

    @Test
    @DisplayName("Organization search endpoint returns the matching organizations")
    fun organizationSearchReturnsMatchingOrganizations() {
        whenever(roleManagement.searchOrganizations("kli"))
            .thenReturn(listOf(OrganizationOption("o1", "Klinik West")))

        mockMvc.perform(get("/api/organizations/search").param("q", "kli"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("q", "kli"))
            .andReturn()

        verify(roleManagement).searchOrganizations("kli")
    }

    // --- Role (value set) search ----------------------------------------------

    @Test
    @DisplayName("Role search endpoint returns the matching roles from the value set")
    fun roleSearchReturnsMatchingRoles() {
        whenever(roleManagement.searchRoles("hau"))
            .thenReturn(listOf(RoleOption("https://pgk/cs|HAP", "Hausarzt")))

        mockMvc.perform(get("/api/roles/search").param("q", "hau"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("q", "hau"))
            .andReturn()

        verify(roleManagement).searchRoles("hau")
    }

    // --- Practitioner select -------------------------------------------------

    @Test
    @DisplayName("Selecting a practitioner exposes its primary organization and existing roles")
    fun selectPractitionerExposesOrganizationAndRoles() {
        whenever(roleManagement.findPrimaryOrganization("Practitioner/1"))
            .thenReturn(OrganizationOption("o1", "Klinik West"))
        whenever(roleManagement.findExistingRoles("Practitioner/1", emptyList(), ""))
            .thenReturn(
                listOf(
                    ExistingRoleRowOption(
                        practitionerRoleId = "role-1",
                        organizationChain = "Klinik West",
                        codes = listOf(RoleCodeOption("https://pgk/cs|HAP", "Hausarzt"))
                    )
                )
            )

        mockMvc.perform(
            get("/api/practitioners/select")
                .param("id", "Practitioner/1")
                .param("name", "Alice Müller")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("practitionerId", "Practitioner/1"))
            .andExpect(model().attribute("practitionerName", "Alice Müller"))
            .andExpect(model().attribute("organizationId", "o1"))
            .andExpect(model().attribute("organizationName", "Klinik West"))
            .andExpect(model().attribute<List<Any>>("existingRoles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(roleManagement).findPrimaryOrganization("Practitioner/1")
        verify(roleManagement).findExistingRoles("Practitioner/1", emptyList(), "")
    }

    @Test
    @DisplayName("Selecting a practitioner without an organization leaves the organization blank")
    fun selectPractitionerWithoutOrganizationLeavesBlank() {
        whenever(roleManagement.findPrimaryOrganization("Practitioner/2")).thenReturn(null)
        whenever(roleManagement.findExistingRoles("Practitioner/2", emptyList())).thenReturn(emptyList())

        mockMvc.perform(
            get("/api/practitioners/select")
                .param("id", "Practitioner/2")
                .param("name", "Bob")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("organizationId", ""))
            .andExpect(model().attribute("organizationName", ""))
            .andReturn()
    }

    @Test
    @DisplayName("Selecting a practitioner uses the root organization passed with the suggestion")
    fun selectPractitionerUsesPassedOrganization() {
        whenever(roleManagement.findExistingRoles("Practitioner/1", emptyList(), "org/akh"))
            .thenReturn(emptyList())

        mockMvc.perform(
            get("/api/practitioners/select")
                .param("id", "Practitioner/1")
                .param("name", "Alice Müller")
                .param("organizationId", "org/akh")
                .param("organizationName", "AKH Wien")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("practitionerId", "Practitioner/1"))
            .andExpect(model().attribute("practitionerName", "Alice Müller"))
            .andExpect(model().attribute("organizationId", "org/akh"))
            .andExpect(model().attribute("organizationName", "AKH Wien"))
            .andReturn()

        verify(roleManagement).findExistingRoles("Practitioner/1", emptyList(), "org/akh")
        verify(roleManagement, never())
            .findPrimaryOrganization("Practitioner/1")
    }

    // --- Organization select -------------------------------------------------

    @Test
    @DisplayName("Select organization endpoint exposes the chosen organization")
    fun selectOrganizationExposesChosenOrganization() {
        mockMvc.perform(
            get("/api/organizations/select")
                .param("id", "o1")
                .param("name", "Klinik West")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("organizationId", "o1"))
            .andExpect(model().attribute("organizationName", "Klinik West"))
            .andReturn()
    }

    // --- Role select / remove -------------------------------------------------

    @Test
    @DisplayName("Select role endpoint adds the role to the selected chips")
    fun selectRoleAddsRoleToChips() {
        whenever(roleChipMapper.toChipOptions(listOf("https://pgk/cs|HAP")))
            .thenReturn(listOf(RoleChipOption("https://pgk/cs|HAP", "Hausarzt")))

        mockMvc.perform(
            get("/api/roles/select")
                .param("id", "https://pgk/cs|HAP")
                .param("roleIds", "")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute<List<Any>>("selectedRoles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(roleChipMapper).toChipOptions(listOf("https://pgk/cs|HAP"))
    }

    @Test
    @DisplayName("Remove role endpoint drops the role from the selected chips")
    fun removeRoleDropsRoleFromChips() {
        whenever(roleChipMapper.toChipOptions(emptyList())).thenReturn(emptyList())

        mockMvc.perform(
            get("/api/roles/remove")
                .param("id", "https://pgk/cs|HAP")
                .param("roleIds", "https://pgk/cs|HAP")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute<List<Any>>("selectedRoles", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()

        verify(roleChipMapper).toChipOptions(emptyList())
    }

    // --- Role removal staging -------------------------------------------------

    @Test
    @DisplayName("Mark removal endpoint stages the role for removal")
    fun markRemovalStagesRoleForRemoval() {
        whenever(roleManagement.findExistingRoles("Practitioner/1", listOf("role-1|https://pgk/cs|HAP")))
            .thenReturn(emptyList())

        mockMvc.perform(
            get("/api/practitioner-roles/mark-removal")
                .param("practitionerId", "Practitioner/1")
                .param("removalKey", "role-1|https://pgk/cs|HAP")
                .param("removedRoleIds", "")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("removedRoleIds", listOf("role-1|https://pgk/cs|HAP")))
            .andReturn()

        verify(roleManagement).findExistingRoles("Practitioner/1", listOf("role-1|https://pgk/cs|HAP"))
    }

    @Test
    @DisplayName("Undo removal endpoint removes the role from the staged removals")
    fun undoRemovalRemovesStagedRole() {
        whenever(roleManagement.findExistingRoles("Practitioner/1", emptyList())).thenReturn(emptyList())

        mockMvc.perform(
            get("/api/practitioner-roles/undo-removal")
                .param("practitionerId", "Practitioner/1")
                .param("removalKey", "role-1|https://pgk/cs|HAP")
                .param("removedRoleIds", "role-1|https://pgk/cs|HAP")
        )
            .andExpect(status().isOk)
            .andExpect(model().attribute("removedRoleIds", emptyList<Any>()))
            .andReturn()

        verify(roleManagement).findExistingRoles("Practitioner/1", emptyList())
    }

    // --- Reset ----------------------------------------------------------------

    @Test
    @DisplayName("Reset endpoint clears the role form")
    fun resetClearsRoleForm() {
        mockMvc.perform(get("/api/roles/reset"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("message", "Formular zurückgesetzt"))
            .andReturn()
    }
}
