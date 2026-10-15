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

package io.cloudflight.pgk.proxy.invitation.domain

import io.cloudflight.pgk.proxy.invitation.domain.model.InvitationRequest
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerRoleInfo
import io.cloudflight.pgk.proxy.invitation.domain.rule.InvitationRule
import io.cloudflight.pgk.proxy.invitation.domain.rule.MxidMatcher
import io.cloudflight.pgk.proxy.invitation.domain.rule.RoleMatcher
import io.cloudflight.pgk.proxy.shared.Decision
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InvitationEvaluatorTest {

    private val evaluator = InvitationEvaluator()
    private val sender = Mxid("@bob:matrix.pgk.example")
    private val request = InvitationRequest(
        sender = sender,
        recipient = Mxid("@alice:other.example"),
        roomId = "room",
        eventId = "event",
        direction = InvitationDirection.INBOUND
    )

    private fun userRule(mxid: Mxid) = InvitationRule.UserPersonal(
        mxidMatcher = MxidMatcher.Exact(mxid),
        roleMatcher = RoleMatcher.Any,
        decision = Decision.DENY
    )

    @Test
    fun `allows when no rules match`() {
        assertEquals(
            Decision.ALLOW,
            evaluator.evaluate(request, listOf(userRule(Mxid("@carol:other.example"))), emptyList(), emptyList())
        )
    }

    @Test
    fun `denies when the sender mxid matches a rule`() {
        assertEquals(
            Decision.DENY,
            evaluator.evaluate(request, listOf(userRule(sender)), emptyList(), emptyList())
        )
    }

    @Test
    fun `denies when a blocked role is attached to the sender`() {
        val roleRule = InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Any,
            roleMatcher = RoleMatcher.ByCode("https://roles.example", "cardio"),
            decision = Decision.DENY
        )
        val senderRoles = listOf(
            PractitionerRoleInfo("cardio", "https://roles.example", TrustLevel.TL_2, "Organization/1")
        )

        assertEquals(Decision.DENY, evaluator.evaluate(request, listOf(roleRule), senderRoles, emptyList()))
    }

    @Test
    fun `allows when the sender has a different role than the blocked one`() {
        val roleRule = InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Any,
            roleMatcher = RoleMatcher.ByCode("https://roles.example", "cardio"),
            decision = Decision.DENY
        )
        val senderRoles = listOf(
            PractitionerRoleInfo("hygiene", "https://roles.example", TrustLevel.TL_2, "Organization/1")
        )

        assertEquals(Decision.ALLOW, evaluator.evaluate(request, listOf(roleRule), senderRoles, emptyList()))
    }

    @Test
    fun `denies when the blocked role is attached to the blocked organization`() {
        val roleRule = InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Any,
            roleMatcher = RoleMatcher.ByOrganizationAndCode("https://roles.example", "cardio", "Organization/1"),
            decision = Decision.DENY
        )
        val senderRoles = listOf(
            PractitionerRoleInfo("cardio", "https://roles.example", TrustLevel.TL_2, "Organization/1")
        )

        assertEquals(Decision.DENY, evaluator.evaluate(request, listOf(roleRule), senderRoles, emptyList()))
    }

    @Test
    fun `allows when the blocked role is attached to a different organization`() {
        val roleRule = InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Any,
            roleMatcher = RoleMatcher.ByOrganizationAndCode("https://roles.example", "cardio", "Organization/1"),
            decision = Decision.DENY
        )
        val senderRoles = listOf(
            PractitionerRoleInfo("cardio", "https://roles.example", TrustLevel.TL_2, "Organization/2")
        )

        assertEquals(Decision.ALLOW, evaluator.evaluate(request, listOf(roleRule), senderRoles, emptyList()))
    }

    @Test
    fun `denies when a domain rule matches the sender's origin`() {
        val domainRule = InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.ByDomain(Fqdn("matrix.pgk.example")),
            roleMatcher = RoleMatcher.Any,
            decision = Decision.DENY
        )

        assertEquals(Decision.DENY, evaluator.evaluate(request, listOf(domainRule), emptyList(), emptyList()))
    }
}
