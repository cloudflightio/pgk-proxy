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

package io.cloudflight.pgk.proxy.ui.application

import io.cloudflight.pgk.proxy.invitation.api.model.OrgBlockedRoles
import io.cloudflight.pgk.proxy.invitation.api.model.RolePermission
import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.port.ChangeInvitationSettingPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesChangePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesTogglePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUsersChangePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUsersQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsQueryPort
import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.port.FindRoleByTokenPort
import io.cloudflight.pgk.proxy.search.api.port.PractitionerSearchPort
import io.cloudflight.pgk.proxy.search.api.port.RoleCatalogPort
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InvitationRightsServiceTest {

    private val invitationRights = mockk<UserInvitationRightsQueryPort>()
    private val changeInvitationSettingPort = mockk<ChangeInvitationSettingPort>()
    private val blockedRolesQuery = mockk<OrgBlockedRolesQueryPort>()
    private val blockedRolesChange = mockk<OrgBlockedRolesChangePort>()
    private val blockedRolesToggle = mockk<OrgBlockedRolesTogglePort>()
    private val blockedUsersQuery = mockk<OrgBlockedUsersQueryPort>()
    private val blockedUsersChange = mockk<OrgBlockedUsersChangePort>()
    private val roleCatalog = mockk<RoleCatalogPort>()
    private val findRoleByToken = mockk<FindRoleByTokenPort>(relaxed = true)
    private val practitionerSearch = mockk<PractitionerSearchPort>()
    private val service = InvitationRightsService(
        invitationRights, changeInvitationSettingPort, blockedRolesQuery, blockedRolesChange,
        blockedRolesToggle, blockedUsersQuery, blockedUsersChange, roleCatalog, findRoleByToken,
        practitionerSearch
    )

    private val catalogRoles = listOf(
        RoleCatalogEntry("https://cs", "HAA", "Fachärztin/Facharzt für Kardiologie"),
        RoleCatalogEntry("https://cs", "CHG", "Fachärztin/Facharzt für Chirurgie"),
        RoleCatalogEntry("https://cs", "HAP", "Hausarzt")
    )

    private fun stubCatalog(blocked: OrgBlockedRoles = OrgBlockedRoles()) {
        every { roleCatalog.loadEntries() } returns catalogRoles
        every { blockedRolesQuery.getBlockedRoles() } returns blocked
    }

    @Test
    fun `dialog roles are filtered case-insensitively on display`() {
        stubCatalog()

        val result = service.getDialogRoles(InvitationDirection.INBOUND, "CHIR", null)

        assertEquals(listOf("https://cs|CHG"), result.map { it.token })
    }

    @Test
    fun `dialog roles are filtered on token as well as display`() {
        stubCatalog()

        val result = service.getDialogRoles(InvitationDirection.OUTBOUND, "hap", null)

        assertEquals(listOf("https://cs|HAP"), result.map { it.token })
    }

    @Test
    fun `empty query returns every dialog role`() {
        stubCatalog()

        val result = service.getDialogRoles(InvitationDirection.INBOUND, "", null)

        assertEquals(setOf("https://cs|HAA", "https://cs|CHG", "https://cs|HAP"), result.map { it.token }.toSet())
    }

    @Test
    fun `dialog filtering keeps the in-progress checked state`() {
        stubCatalog()

        val result = service.getDialogRoles(InvitationDirection.INBOUND, "Fachärztin", setOf("https://cs|CHG"))

        assertEquals(setOf("https://cs|CHG"), result.filter { it.blocked }.map { it.token }.toSet())
    }

    @Test
    fun `dialog roles fall back to the stored block list when no checked state is submitted`() {
        stubCatalog(blocked = OrgBlockedRoles(receiveBlockedRoles = listOf(RolePermission("https://cs|HAA", "HAA"))))

        val result = service.getDialogRoles(InvitationDirection.INBOUND, "", null)

        assertEquals(setOf("https://cs|HAA"), result.filter { it.blocked }.map { it.token }.toSet())
    }

    // --- Practitioner list --------------------------------------------------

    @Test
    fun `practitioner list only contains users with an IDP mxid`() {
        every { invitationRights.getPractitionerRights() } returns listOf(
            InvitationRightsEntry("p-1", "Dr. Anna Berger", mxid = Mxid("@anna:matrix.pgk.example")),
            InvitationRightsEntry("p-2", "Max Huber", mxid = null)
        )

        val result = service.listPractitioners()

        assertEquals(listOf("p-1"), result.map { it.id })
        assertEquals("Dr. Anna Berger", result.single().name)
        assertEquals("@anna:matrix.pgk.example", result.single().subline)
    }

    // --- Blocked users ------------------------------------------------------

    @Test
    fun `blocked user list contains only the manually blocked mxids with a resolved display name`() {
        every { invitationRights.getPractitionerRights() } returns listOf(
            InvitationRightsEntry("p-1", "Anna Berger", mxid = Mxid("@anna:matrix.pgk.example"))
        )
        every { blockedUsersQuery.getBlockedUsers() } returns setOf(
            Mxid("@harasser:evil.example"),
            Mxid("@anna:matrix.pgk.example")
        )

        val result = service.listBlockedUsers()

        assertEquals(2, result.size)
        assertEquals("Anna Berger", result.first { it.mxid == "@anna:matrix.pgk.example" }.name)
        assertEquals("@harasser:evil.example", result.first { it.mxid == "@harasser:evil.example" }.name)
    }

    @Test
    fun `setBlockedUser delegates to the change port`() {
        every { blockedUsersChange.setBlockedUser(Mxid("@anna:matrix.pgk.example"), true) } returns Unit

        service.setBlockedUser("@anna:matrix.pgk.example", true)

        verify(exactly = 1) { blockedUsersChange.setBlockedUser(Mxid("@anna:matrix.pgk.example"), true) }
    }

    @Test
    fun `toggleBlockedRole delegates to the toggle port`() {
        every { blockedRolesToggle.toggleBlockedRole(InvitationDirection.OUTBOUND, "https://cs|HAP") } returns Unit

        service.toggleBlockedRole(InvitationDirection.OUTBOUND, "https://cs|HAP")

        verify(exactly = 1) {
            blockedRolesToggle.toggleBlockedRole(InvitationDirection.OUTBOUND, "https://cs|HAP")
        }
    }

    @Test
    fun `searchUsers maps practitioners to suggestions and drops entries without an mxid`() {
        every { practitionerSearch.searchPractitioners("anna") } returns listOf(
            mapOf(
                "id" to "Practitioner/1", "name" to "Anna Berger",
                "trustLevel" to "TL_2", "mxid" to "@anna:matrix.pgk.example"
            ),
            mapOf(
                "id" to "Practitioner/2", "name" to "Ohne Matrix",
                "trustLevel" to "TL_1", "mxid" to ""
            )
        )

        val result = service.searchUsers("anna")

        assertEquals(
            listOf(
                PractitionerOption(
                    id = "Practitioner/1", name = "Anna Berger",
                    trustLevel = "TL_2", mxid = "@anna:matrix.pgk.example"
                )
            ),
            result
        )
    }
}
