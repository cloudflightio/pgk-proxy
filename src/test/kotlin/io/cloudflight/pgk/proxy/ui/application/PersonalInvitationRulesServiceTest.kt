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

import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.model.PersonalInvitationRule
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersRemovePort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesRemovePort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsQueryPort
import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleOption
import io.cloudflight.pgk.proxy.search.api.port.CurrentUserMxidQueryPort
import io.cloudflight.pgk.proxy.search.api.port.FindRoleByTokenPort
import io.cloudflight.pgk.proxy.search.api.port.PractitionerSearchPort
import io.cloudflight.pgk.proxy.search.api.port.RoleSearchPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalInvitationRulesServiceTest {

    private val currentUserMxidQuery = mockk<CurrentUserMxidQueryPort>()
    private val personalRulesQuery = mockk<PersonalInvitationRulesQueryPort>()
    private val personalRulesAdd = mockk<PersonalInvitationRulesAddPort>()
    private val personalRulesRemove = mockk<PersonalInvitationRulesRemovePort>()
    private val blockedUsersQuery = mockk<PersonalBlockedUsersQueryPort>()
    private val blockedUsersAdd = mockk<PersonalBlockedUsersAddPort>()
    private val blockedUsersRemove = mockk<PersonalBlockedUsersRemovePort>()
    private val userInvitationRights = mockk<UserInvitationRightsQueryPort>()
    private val roleSearch = mockk<RoleSearchPort>()
    private val findRoleByToken = mockk<FindRoleByTokenPort>()
    private val practitionerSearch = mockk<PractitionerSearchPort>()
    private val service = PersonalInvitationRulesService(
        currentUserMxidQuery, personalRulesQuery, personalRulesAdd, personalRulesRemove,
        blockedUsersQuery, blockedUsersAdd, blockedUsersRemove, userInvitationRights,
        roleSearch, findRoleByToken, practitionerSearch
    )

    private val owner = Mxid("@alice:other.example")

    @Test
    fun `listRules returns a flat list sorted case-insensitively by roleDisplay and falls back to raw values for blank displays`() {
        every { personalRulesQuery.getRules(owner.value) } returns listOf(
            PersonalInvitationRule("Organization/1", "AKH Wien", "sys|CARD", "Kardiologie"),
            PersonalInvitationRule("Organization/2", "Klinik Nord", "sys|HAU", "hausarzt"),
            PersonalInvitationRule("Organization/3", "", "sys|ZAHN", "")
        )

        val result = service.listRules(owner)

        assertEquals(
            listOf(
                PersonalInvitationRuleView("Organization/2", "Klinik Nord", "sys|HAU", "hausarzt"),
                PersonalInvitationRuleView("Organization/1", "AKH Wien", "sys|CARD", "Kardiologie"),
                PersonalInvitationRuleView("Organization/3", "Organization/3", "sys|ZAHN", "sys|ZAHN")
            ),
            result
        )
    }

    @Test
    fun `searchRoles keeps checked-but-unmatched tokens visible and checked`() {
        every { roleSearch.searchRoles("c") } returns listOf(
            mapOf(
                "id" to "sys|cardio",
                "codeDisplay" to "Kardiologie",
                "title" to "Fachärztin/Facharzt für Kardiologie"
            )
        )
        every { findRoleByToken.findByToken("sys|radio") } returns RoleCatalogEntry("sys", "radio", "Radiologie")

        val result = service.searchRoles("c", setOf("sys|cardio", "sys|radio"))

        assertEquals(listOf("sys|radio", "sys|cardio"), result.map { it.id })
        assertEquals(listOf("Radiologie", "Kardiologie"), result.map { it.codeDisplay })
        assertTrue(result.all { it.blocked })
    }

    @Test
    fun `searchRoles keeps an unknown checked token visible with the raw token as display`() {
        every { roleSearch.searchRoles("c") } returns emptyList()
        every { findRoleByToken.findByToken("sys|weird") } returns null

        val result = service.searchRoles("c", setOf("sys|weird"))

        assertEquals(listOf(RoleOption("sys|weird", "sys|weird", "", true)), result)
    }

    @Test
    fun `addRules adds each distinct non-blank token once with an empty organization and resolved roleDisplay`() {
        every { findRoleByToken.findByToken("sys|cardio") } returns RoleCatalogEntry("sys", "cardio", "Kardiologie")
        every { findRoleByToken.findByToken("sys|hyg") } returns null
        every { personalRulesAdd.addRule(any(), any(), any(), any(), any()) } just Runs

        service.addRules(owner, listOf("sys|cardio", "sys|cardio", "   ", "sys|hyg"))

        verify(exactly = 2) { personalRulesAdd.addRule(any(), any(), any(), any(), any()) }
        verify(exactly = 1) {
            personalRulesAdd.addRule(owner.value, "", "sys|cardio", "", "Kardiologie")
        }
        verify(exactly = 1) {
            personalRulesAdd.addRule(owner.value, "", "sys|hyg", "", "")
        }
    }

    @Test
    fun `removeRule delegates with an empty organization`() {
        every { personalRulesRemove.removeRule(any(), any(), any()) } just Runs

        service.removeRule(owner, "sys|cardio")

        verify(exactly = 1) { personalRulesRemove.removeRule(owner.value, "", "sys|cardio") }
    }

    @Test
    fun `searchRoles with a blank query still calls the role search port`() {
        every { roleSearch.searchRoles("") } returns listOf(
            mapOf("id" to "sys|cardio", "codeDisplay" to "Kardiologie", "title" to "")
        )

        val result = service.searchRoles("", emptySet())

        assertEquals(listOf("sys|cardio"), result.map { it.id })
        verify(exactly = 1) { roleSearch.searchRoles("") }
    }

    @Test
    fun `listBlockedUsers resolves known users by directory name and falls back to the mxid`() {
        every { userInvitationRights.getPractitionerRights() } returns listOf(
            InvitationRightsEntry("p1", "Anna Berger", Mxid("@anna:matrix.example"), receive = true, send = true),
            InvitationRightsEntry("p2", "Bob Beispiel", Mxid("@bob:matrix.example"), receive = true, send = true)
        )
        every { blockedUsersQuery.getBlockedUsers(owner) } returns listOf(
            Mxid("@harasser:evil.example"),
            Mxid("@bob:matrix.example")
        )

        val result = service.listBlockedUsers(owner)

        assertEquals(
            listOf(
                BlockedUserRow("@harasser:evil.example", "@harasser:evil.example"),
                BlockedUserRow("@bob:matrix.example", "Bob Beispiel")
            ),
            result
        )
    }

    @Test
    fun `blockUser trims the mxid and delegates to the add port`() {
        every { blockedUsersAdd.addBlockedUser(any(), any()) } just Runs

        service.blockUser(owner, "  @harasser:evil.example  ")

        verify(exactly = 1) { blockedUsersAdd.addBlockedUser(owner, Mxid("@harasser:evil.example")) }
    }

    @Test
    fun `unblockUser delegates to the remove port`() {
        every { blockedUsersRemove.removeBlockedUser(any(), any()) } just Runs

        service.unblockUser(owner, "@harasser:evil.example")

        verify(exactly = 1) { blockedUsersRemove.removeBlockedUser(owner, Mxid("@harasser:evil.example")) }
    }

    @Test
    fun `searchUsers maps practitioners to suggestions and drops entries without an mxid`() {
        every { practitionerSearch.searchPractitioners("anna") } returns listOf(
            mapOf(
                "id" to "Practitioner/1", "name" to "Anna Berger",
                "trustLevel" to "TL_2", "mxid" to "@anna:matrix.example"
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
                    trustLevel = "TL_2", mxid = "@anna:matrix.example"
                )
            ),
            result
        )
    }
}
