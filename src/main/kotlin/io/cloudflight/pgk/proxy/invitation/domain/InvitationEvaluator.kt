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
import io.cloudflight.pgk.proxy.shared.Mxid
import org.springframework.stereotype.Component

/**
 * Only the personal blacklist (rule type [InvitationRule.UserPersonal]) is implemented so far;
 * a rule whose matchers hit the sender and whose [InvitationRule.decision] is DENY blocks the
 * invitation. Rules are global: they apply regardless of the origin server.
 */
@Component
class InvitationEvaluator {

    fun evaluate(
        request: InvitationRequest,
        rules: List<InvitationRule>,
        senderRoles: List<PractitionerRoleInfo>,
        recipientRoles: List<PractitionerRoleInfo>
    ): Decision {
        val blocked = rules.any { it.matches(request.sender, senderRoles) && it.decision == Decision.DENY }
        return if (blocked) Decision.DENY else Decision.ALLOW
    }

    private fun InvitationRule.matches(senderMxid: Mxid, senderRoles: List<PractitionerRoleInfo>): Boolean {
        val mxidMatch = when (val matcher = mxidMatcher) {
            is MxidMatcher.Exact -> matcher.mxid == senderMxid
            is MxidMatcher.ByDomain -> matcher.fqdn.value == senderMxid.domain
            MxidMatcher.Any -> true
        }
        val roleMatch = when (val matcher = roleMatcher) {
            is RoleMatcher.ByCode -> senderRoles.any { it.codeSystem == matcher.system && it.code == matcher.code }
            is RoleMatcher.ByOrganizationAndCode -> senderRoles.any {
                it.codeSystem == matcher.system && it.code == matcher.code && it.organizationReference == matcher.organizationReference
            }
            is RoleMatcher.ByTrustLevel -> senderRoles.any { it.trustLevel == matcher.trustLevel }
            RoleMatcher.Any -> true
        }
        return mxidMatch && roleMatch
    }
}
