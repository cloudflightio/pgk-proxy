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

import io.cloudflight.pgk.proxy.invitation.api.model.ChangeInvitationRights
import io.cloudflight.pgk.proxy.invitation.api.model.OrgBlockedRoles
import io.cloudflight.pgk.proxy.invitation.api.model.RolePermission
import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.BlockedRoleOption
import io.cloudflight.pgk.proxy.ui.application.BlockedUserRow
import io.cloudflight.pgk.proxy.ui.application.InvitationRightsRow
import io.cloudflight.pgk.proxy.ui.application.InvitationRightsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anySet
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

class InvitationRightsControllerTest {

    private val invitationRights = org.mockito.Mockito.mock(InvitationRightsService::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(InvitationRightsController(invitationRights, AuditLogger()))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setViewResolvers(noOpViewResolver())
            .build()
    }

    /**
     * Standalone MockMvc has no Thymeleaf resolver; without one, the plain page view name
     * `invitation-rights` falls through to the default internal-resource resolver and trips
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

    @Test
    @DisplayName("Invitation rights page renders with rows from the service")
    fun pageRendersPractitionersFromPort() {
        whenever(invitationRights.listPractitioners())
            .thenReturn(
                listOf(
                    InvitationRightsRow("1", "Dr. Anna Berger", "Abteilung Kardiochirurgie", "AB", true, true)
                )
            )

        mockMvc.perform(get("/admin/invitation-rights"))
            .andExpect(status().isOk)
            .andExpect(view().name("invitation-rights"))
            .andExpect(model().attribute<List<Any>>("practitioners", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(invitationRights).listPractitioners()
    }

    @Test
    @DisplayName("Invitation rights page keeps the role block lists empty by default")
    fun pageKeepsRoleBlockListsEmpty() {
        whenever(invitationRights.listPractitioners()).thenReturn(emptyList())
        whenever(invitationRights.getBlockedRoles()).thenReturn(OrgBlockedRoles())

        mockMvc.perform(get("/admin/invitation-rights"))
            .andExpect(status().isOk)
            .andExpect(model().attribute<List<RolePermission>>("receiveBlockedRoles", org.hamcrest.Matchers.hasSize(0)))
            .andExpect(model().attribute<List<RolePermission>>("sendBlockedRoles", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()
    }

    @Test
    @DisplayName("Invitation rights page renders the blocked users from the service")
    fun pageRendersBlockedUsers() {
        whenever(invitationRights.listPractitioners()).thenReturn(emptyList())
        whenever(invitationRights.listBlockedUsers())
            .thenReturn(
                listOf(BlockedUserRow("@harasser:evil.example", "@harasser:evil.example"))
            )

        mockMvc.perform(get("/admin/invitation-rights"))
            .andExpect(status().isOk)
            .andExpect(model().attribute<List<BlockedUserRow>>("blockedUsers", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(invitationRights).listBlockedUsers()
    }

    @Test
    @DisplayName("Invitation rights page renders the blocked role lists from the service")
    fun pageRendersRoleBlockLists() {
        whenever(invitationRights.listPractitioners()).thenReturn(emptyList())
        whenever(invitationRights.getBlockedRoles())
            .thenReturn(
                OrgBlockedRoles(
                    receiveBlockedRoles = listOf(RolePermission("https://cs|HAA", "HAA")),
                    sendBlockedRoles = listOf(RolePermission("https://cs|CHG", "CHG"))
                )
            )

        mockMvc.perform(get("/admin/invitation-rights"))
            .andExpect(status().isOk)
            .andExpect(model().attribute<List<RolePermission>>("receiveBlockedRoles", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute<List<RolePermission>>("sendBlockedRoles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(invitationRights).getBlockedRoles()
    }

    // --- Receive toggle ------------------------------------------------------

    @Test
    @DisplayName("Receive toggle persists the new value and returns the receive switch fragment")
    fun updateReceivePersistsAndReturnsFragment() {
        mockMvc.perform(post("/admin/invitation-rights/1/receive/false"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/switches :: receive-switch"))
            .andExpect(model().attribute("practitionerId", "1"))
            .andExpect(model().attribute("switchValue", false))
            .andReturn()

        verify(invitationRights).changeInvitationSetting(
            ChangeInvitationRights(id = "1", receive = false, send = null)
        )
    }

    // --- Send toggle ---------------------------------------------------------

    @Test
    @DisplayName("Send toggle persists the new value and returns the send switch fragment")
    fun updateSendPersistsAndReturnsFragment() {
        mockMvc.perform(post("/admin/invitation-rights/1/send/true"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/switches :: send-switch"))
            .andExpect(model().attribute("practitionerId", "1"))
            .andExpect(model().attribute("switchValue", true))
            .andReturn()

        verify(invitationRights).changeInvitationSetting(
            ChangeInvitationRights(id = "1", send = true, receive = null)
        )
    }

    // --- Toggle failure ------------------------------------------------------

    @Test
    @DisplayName("Failed receive toggle returns the switch in its unchanged state with an error notification")
    fun failedToggleKeepsUnchangedState() {
        doThrow(RuntimeException("fhir down"))
            .whenever(invitationRights).changeInvitationSetting(
                ChangeInvitationRights(id = "1", receive = true, send = null)
            )

        mockMvc.perform(post("/admin/invitation-rights/1/receive/true"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/switches :: switch-error"))
            .andExpect(model().attribute("practitionerId", "1"))
            .andExpect(model().attribute("switchValue", false))
            .andExpect(model().attribute("switchType", "receive"))
            .andExpect(model().attribute("error", "Änderung fehlgeschlagen"))
            .andReturn()
    }

    // --- Blocked-roles dialog ------------------------------------------------

    @Test
    @DisplayName("Dialog for a direction renders the role list and the receive title")
    fun dialogRendersRolesForDirection() {
        whenever(invitationRights.getDialogRoles(InvitationDirection.INBOUND, "", null))
            .thenReturn(
                listOf(
                    BlockedRoleOption("https://cs|HAA", "Fachärztin/Facharzt für Kardiologie", true),
                    BlockedRoleOption("https://cs|CHG", "Fachärztin/Facharzt für Chirurgie", false)
                )
            )

        mockMvc.perform(get("/admin/invitation-rights/roles/receive/dialog"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles-dialog :: dialog"))
            .andExpect(model().attribute("direction", "receive"))
            .andExpect(model().attribute("title", "Gesperrte Rollen festlegen – Einladungen empfangen"))
            .andExpect(model().attribute<List<BlockedRoleOption>>("roles", org.hamcrest.Matchers.hasSize(2)))
            .andReturn()

        verify(invitationRights).getDialogRoles(InvitationDirection.INBOUND, "", null)
    }

    @Test
    @DisplayName("Dialog for the send direction renders the send title")
    fun dialogRendersSendTitle() {
        whenever(invitationRights.getDialogRoles(InvitationDirection.OUTBOUND, "", null)).thenReturn(emptyList())

        mockMvc.perform(get("/admin/invitation-rights/roles/send/dialog"))
            .andExpect(status().isOk)
            .andExpect(model().attribute("title", "Gesperrte Rollen festlegen – Einladungen senden"))
            .andReturn()
    }

    @Test
    @DisplayName("Dialog with a query forwards the filter to the service and returns the role-list fragment")
    fun dialogFiltersRolesByQuery() {
        whenever(invitationRights.getDialogRoles(InvitationDirection.INBOUND, "chir", null))
            .thenReturn(
                listOf(BlockedRoleOption("https://cs|CHG", "Fachärztin/Facharzt für Chirurgie", false))
            )

        mockMvc.perform(get("/admin/invitation-rights/roles/receive/dialog").param("q", "chir"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles-dialog :: role-list"))
            .andExpect(model().attribute("q", "chir"))
            .andExpect(model().attribute<List<BlockedRoleOption>>("roles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(invitationRights).getDialogRoles(InvitationDirection.INBOUND, "chir", null)
    }

    @Test
    @DisplayName("Dialog filtering forwards the in-progress checked state to the service")
    fun dialogFilteringPreservesCheckedState() {
        whenever(invitationRights.getDialogRoles(InvitationDirection.INBOUND, "Fachärztin", setOf("https://cs|CHG")))
            .thenReturn(
                listOf(
                    BlockedRoleOption("https://cs|HAA", "Fachärztin/Facharzt für Kardiologie", false),
                    BlockedRoleOption("https://cs|CHG", "Fachärztin/Facharzt für Chirurgie", true)
                )
            )

        mockMvc.perform(
            get("/admin/invitation-rights/roles/receive/dialog")
                .param("q", "Fachärztin")
                .param("blockedRoles", "https://cs|CHG")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles-dialog :: role-list"))
            .andReturn()

        verify(invitationRights).getDialogRoles(InvitationDirection.INBOUND, "Fachärztin", setOf("https://cs|CHG"))
    }

    @Test
    @DisplayName("Unknown dialog direction is rejected")
    fun dialogRejectsUnknownDirection() {
        mockMvc.perform(get("/admin/invitation-rights/roles/unknown/dialog"))
            .andExpect(status().isBadRequest)
            .andReturn()
    }

    // --- Blocked-roles panel + save ------------------------------------------

    @Test
    @DisplayName("Panel endpoint renders both rows from the service")
    fun panelRendersBothRows() {
        whenever(invitationRights.getBlockedRoles())
            .thenReturn(
                OrgBlockedRoles(
                    receiveBlockedRoles = listOf(RolePermission("https://cs|HAA", "HAA"))
                )
            )

        mockMvc.perform(get("/admin/invitation-rights/roles/panel"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles :: panel"))
            .andExpect(model().attribute<List<RolePermission>>("receiveBlockedRoles", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute<List<RolePermission>>("sendBlockedRoles", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()
    }

    @Test
    @DisplayName("Save replaces the full blocked set for the direction and returns the save-ok marker")
    fun saveReplacesBlockedRolesAndReturnsSuccess() {
        mockMvc.perform(
            post("/admin/invitation-rights/roles/receive")
                .param("blockedRoles", "https://cs|HAA", "https://cs|CHG")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles :: save-ok"))
            .andExpect(model().attribute("direction", "receive"))
            .andReturn()

        verify(invitationRights).replaceBlockedRoles(
            InvitationDirection.INBOUND, setOf("https://cs|HAA", "https://cs|CHG")
        )
    }

    @Test
    @DisplayName("Save without checked roles clears the direction")
    fun saveWithoutRolesClearsDirection() {
        mockMvc.perform(post("/admin/invitation-rights/roles/send"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles :: save-ok"))
            .andReturn()

        verify(invitationRights).replaceBlockedRoles(InvitationDirection.OUTBOUND, emptySet())
    }

    @Test
    @DisplayName("Failed save returns the inline save-error fragment")
    fun failedSaveReturnsInlineError() {
        doThrow(RuntimeException("db down"))
            .whenever(invitationRights).replaceBlockedRoles(eq(InvitationDirection.OUTBOUND), anySet())

        mockMvc.perform(post("/admin/invitation-rights/roles/send"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles :: save-error"))
            .andExpect(model().attribute("direction", "send"))
            .andReturn()
    }

    // --- Single role toggle -------------------------------------------------

    @Test
    @DisplayName("Toggle flips a single blocked role and returns the panel")
    fun toggleRoleReturnsPanel() {
        mockMvc.perform(post("/admin/invitation-rights/roles/receive/toggle").param("roleToken", "https://cs|HAA"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-roles :: panel"))
            .andReturn()

        verify(invitationRights).toggleBlockedRole(InvitationDirection.INBOUND, "https://cs|HAA")
    }

    // --- Blocked user add / remove ------------------------------------------

    @Test
    @DisplayName("Adding a blocked user persists the mxid and returns the panel")
    fun addBlockedUserPersistsAndReturnsPanel() {
        mockMvc.perform(post("/admin/invitation-rights/users/add").param("mxid", "@harasser:evil.example"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-users :: panel"))
            .andReturn()

        verify(invitationRights).setBlockedUser("@harasser:evil.example", true)
    }

    @Test
    @DisplayName("Adding an invalid mxid shows an inline error and keeps the panel")
    fun addBlockedUserRejectsInvalidMxid() {
        doThrow(IllegalArgumentException("bad mxid"))
            .whenever(invitationRights).setBlockedUser("no-at-sign", true)

        mockMvc.perform(post("/admin/invitation-rights/users/add").param("mxid", "no-at-sign"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-users :: panel"))
            .andExpect(model().attributeExists("error"))
            .andReturn()
    }

    @Test
    @DisplayName("Removing a blocked user persists the unblock and returns the panel")
    fun removeBlockedUserPersistsAndReturnsPanel() {
        mockMvc.perform(post("/admin/invitation-rights/users/remove").param("mxid", "@harasser:evil.example"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-users :: panel"))
            .andReturn()

        verify(invitationRights).setBlockedUser("@harasser:evil.example", false)
    }

    // --- Blocked-user search ------------------------------------------------

    @Test
    @DisplayName("Blocked-user search returns matching users as suggestions")
    fun searchBlockedUsersReturnsSuggestions() {
        whenever(invitationRights.searchUsers("anna")).thenReturn(
            listOf(PractitionerOption("Practitioner/1", "Anna Berger", "TL_2", "@anna:matrix.pgk.example"))
        )

        mockMvc.perform(get("/admin/invitation-rights/users/search").param("mxid", "anna"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-users :: suggestions"))
            .andExpect(model().attribute("q", "anna"))
            .andExpect(model().attribute<List<Any>>("items", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(invitationRights).searchUsers("anna")
    }

    @Test
    @DisplayName("Blocked-user search is empty for a blank query")
    fun searchBlockedUsersIsEmptyForBlankQuery() {
        mockMvc.perform(get("/admin/invitation-rights/users/search").param("mxid", "  "))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/blocked-users :: suggestions"))
            .andExpect(model().attribute<List<Any>>("items", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()

        org.mockito.kotlin.verify(invitationRights, org.mockito.kotlin.never()).searchUsers(org.mockito.kotlin.any())
    }

}
