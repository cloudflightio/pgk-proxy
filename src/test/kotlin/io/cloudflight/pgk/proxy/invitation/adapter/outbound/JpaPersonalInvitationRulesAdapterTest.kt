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

import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalBlockedUserEntity
import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalBlockedUserId
import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalBlockedUserRepository
import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalInvitationBlockEntity
import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalInvitationBlockId
import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.PersonalInvitationBlockRepository
import io.cloudflight.pgk.proxy.invitation.api.model.PersonalInvitationRule
import io.cloudflight.pgk.proxy.invitation.domain.rule.InvitationRule
import io.cloudflight.pgk.proxy.invitation.domain.rule.MxidMatcher
import io.cloudflight.pgk.proxy.invitation.domain.rule.RoleMatcher
import io.cloudflight.pgk.proxy.shared.Decision
import io.cloudflight.pgk.proxy.shared.Mxid
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JpaPersonalInvitationRulesAdapterTest {

    private val repository = mockk<PersonalInvitationBlockRepository>()
    private val blockedUserRepository = mockk<PersonalBlockedUserRepository>()
    private val adapter = JpaPersonalInvitationRulesAdapter(repository, blockedUserRepository)
    private val owner = "@alice:other.example"

    @Test
    fun `maps rows to global by-code deny rules ignoring the stored organization`() {
        every { repository.findByOwnerMxid(owner) } returns listOf(
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "Organization/1", "https://roles.example|cardio"),
                organizationDisplay = "AKH Wien",
                roleDisplay = "Kardiologie"
            ),
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "Organization/2", "https://roles.example|cardio"),
                organizationDisplay = "Klinik Nord",
                roleDisplay = "Kardiologie"
            )
        )
        every { blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(owner) } returns emptyList()

        val rules = adapter.getRulesFor(Mxid(owner))

        assertEquals(2, rules.size)
        rules.forEach { rule ->
            val userRule = rule as InvitationRule.UserPersonal
            assertEquals(MxidMatcher.Any, userRule.mxidMatcher)
            assertEquals(
                RoleMatcher.ByCode(
                    system = "https://roles.example",
                    code = "cardio"
                ),
                userRule.roleMatcher
            )
            assertEquals(Decision.DENY, userRule.decision)
        }
    }

    @Test
    fun `skips malformed rows so they cannot block everything`() {
        every { repository.findByOwnerMxid(owner) } returns listOf(
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "", ""),
                organizationDisplay = "",
                roleDisplay = "blank token"
            ),
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "", "noSeparator"),
                organizationDisplay = "",
                roleDisplay = "no pipe"
            ),
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "", "https://roles.example|"),
                organizationDisplay = "",
                roleDisplay = "blank code"
            )
        )
        every { blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(owner) } returns emptyList()

        assertTrue(adapter.getRulesFor(Mxid(owner)).isEmpty())
    }

    @Test
    fun `returns api dtos with the stored organization and display values`() {
        every { repository.findByOwnerMxid(owner) } returns listOf(
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "Organization/1", "sys|cardio"),
                organizationDisplay = "AKH Wien",
                roleDisplay = "Fachärztin/Facharzt für Kardiologie"
            )
        )

        assertEquals(
            listOf(
                PersonalInvitationRule(
                    organization = "Organization/1",
                    organizationDisplay = "AKH Wien",
                    roleToken = "sys|cardio",
                    roleDisplay = "Fachärztin/Facharzt für Kardiologie"
                )
            ),
            adapter.getRules(owner)
        )
    }

    @Test
    fun `add rule with an empty organization does not throw and saves`() {
        every { repository.existsById(PersonalInvitationBlockId(owner, "", "sys|cardio")) } returns false
        val saved = slot<PersonalInvitationBlockEntity>()
        every { repository.save(capture(saved)) } returnsArgument 0

        adapter.addRule(owner, "", "sys|cardio", "", "Kardiologie")

        assertEquals(PersonalInvitationBlockId(owner, "", "sys|cardio"), saved.captured.id)
        assertEquals("", saved.captured.organizationDisplay)
        assertEquals("Kardiologie", saved.captured.roleDisplay)
    }

    @Test
    fun `add rule is idempotent for an existing entry`() {
        every { repository.existsById(PersonalInvitationBlockId(owner, "", "sys|cardio")) } returns true

        adapter.addRule(owner, "", "sys|cardio", "", "Kardiologie")

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `rejects rules with a blank role token`() {
        var rejected = false
        try {
            adapter.addRule(owner, "", "  ", "", "Kardiologie")
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `remove rule is idempotent when the row is missing`() {
        every { repository.deleteRule(any(), any(), any()) } just Runs

        adapter.removeRule(owner, "", "sys|cardio")

        verify(exactly = 1) {
            repository.deleteRule(owner, "", "sys|cardio")
        }
    }

    @Test
    fun `maps blocked users to exact-mxid deny rules and combines them with role rules`() {
        every { repository.findByOwnerMxid(owner) } returns listOf(
            PersonalInvitationBlockEntity(
                PersonalInvitationBlockId(owner, "", "https://roles.example|cardio"),
                organizationDisplay = "",
                roleDisplay = "Kardiologie"
            )
        )
        every { blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(owner) } returns listOf(
            PersonalBlockedUserEntity(PersonalBlockedUserId(owner, "@harasser:evil.example")),
            PersonalBlockedUserEntity(PersonalBlockedUserId(owner, "@bob:matrix.pgk.example"))
        )

        val rules = adapter.getRulesFor(Mxid(owner))

        assertEquals(3, rules.size)
        val mxidRules = rules.filterIsInstance<InvitationRule.UserPersonal>()
            .filter { it.mxidMatcher is MxidMatcher.Exact }
        assertEquals(
            listOf("@harasser:evil.example", "@bob:matrix.pgk.example"),
            mxidRules.map { (it.mxidMatcher as MxidMatcher.Exact).mxid.value }
        )
        mxidRules.forEach { rule ->
            assertEquals(RoleMatcher.Any, rule.roleMatcher)
            assertEquals(Decision.DENY, rule.decision)
        }
    }

    @Test
    fun `returns the personal blocked users of an owner`() {
        every { blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(owner) } returns listOf(
            PersonalBlockedUserEntity(PersonalBlockedUserId(owner, "@harasser:evil.example")),
            PersonalBlockedUserEntity(PersonalBlockedUserId(owner, "@bob:matrix.pgk.example"))
        )

        assertEquals(
            listOf(
                Mxid("@harasser:evil.example"),
                Mxid("@bob:matrix.pgk.example")
            ),
            adapter.getBlockedUsers(Mxid(owner))
        )
    }

    @Test
    fun `adds a blocked user idempotently`() {
        every { blockedUserRepository.existsById(PersonalBlockedUserId(owner, "@harasser:evil.example")) } returns false
        val saved = slot<PersonalBlockedUserEntity>()
        every { blockedUserRepository.save(capture(saved)) } returnsArgument 0

        adapter.addBlockedUser(Mxid(owner), Mxid("@harasser:evil.example"))

        assertEquals(PersonalBlockedUserId(owner, "@harasser:evil.example"), saved.captured.id)
    }

    @Test
    fun `does not save a blocked user that already exists`() {
        every { blockedUserRepository.existsById(PersonalBlockedUserId(owner, "@harasser:evil.example")) } returns true

        adapter.addBlockedUser(Mxid(owner), Mxid("@harasser:evil.example"))

        verify(exactly = 0) { blockedUserRepository.save(any()) }
    }

    @Test
    fun `rejects blocking a blank mxid`() {
        var rejected = false
        try {
            adapter.addBlockedUser(Mxid(owner), Mxid(""))
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun `removes a blocked user idempotently`() {
        every { blockedUserRepository.deleteByIdOwnerMxidAndIdBlockedMxid(any(), any()) } just Runs

        adapter.removeBlockedUser(Mxid(owner), Mxid("@harasser:evil.example"))

        verify(exactly = 1) {
            blockedUserRepository.deleteByIdOwnerMxidAndIdBlockedMxid(owner, "@harasser:evil.example")
        }
    }
}
