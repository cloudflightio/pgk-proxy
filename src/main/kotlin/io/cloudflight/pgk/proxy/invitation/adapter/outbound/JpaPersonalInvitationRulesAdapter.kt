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
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersRemovePort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesRemovePort
import io.cloudflight.pgk.proxy.invitation.domain.rule.InvitationRule
import io.cloudflight.pgk.proxy.invitation.domain.rule.MxidMatcher
import io.cloudflight.pgk.proxy.invitation.domain.rule.RoleMatcher
import io.cloudflight.pgk.proxy.invitation.port.InvitationRulePort
import io.cloudflight.pgk.proxy.shared.Decision
import io.cloudflight.pgk.proxy.shared.Mxid
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Exposes the rules to the maintenance UI via the api ports and to the federation
 * enforcement path via [InvitationRulePort]. Each stored row maps to a
 * [InvitationRule.UserPersonal] DENY rule. Role rules are global: a stored role token blocks
 * the sender's matching role regardless of which organization it is attached to; new rows
 * are stored with an empty organization. Blocked-user rows map to an exact-mxid DENY rule.
 * An empty rule set for a recipient means all-allowed.
 */
@Component
class JpaPersonalInvitationRulesAdapter(
    private val repository: PersonalInvitationBlockRepository,
    private val blockedUserRepository: PersonalBlockedUserRepository
) : InvitationRulePort, PersonalInvitationRulesQueryPort, PersonalInvitationRulesAddPort,
    PersonalInvitationRulesRemovePort, PersonalBlockedUsersQueryPort, PersonalBlockedUsersAddPort,
    PersonalBlockedUsersRemovePort {

    @Transactional(readOnly = true)
    override fun getRulesFor(recipient: Mxid): List<InvitationRule> =
        repository.findByOwnerMxid(recipient.value).mapNotNull { it.toRuleOrNull() } +
        blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(recipient.value).mapNotNull { it.toRuleOrNull() }

    @Transactional(readOnly = true)
    override fun getRules(ownerMxid: String): List<PersonalInvitationRule> =
        repository.findByOwnerMxid(ownerMxid).map {
            PersonalInvitationRule(
                organization = it.id.organization,
                organizationDisplay = it.organizationDisplay,
                roleToken = it.id.roleToken,
                roleDisplay = it.roleDisplay
            )
        }

    @Transactional
    override fun addRule(
        ownerMxid: String,
        organization: String,
        roleToken: String,
        organizationDisplay: String,
        roleDisplay: String
    ) {
        require(roleToken.isNotBlank()) { "Rule role must not be blank" }
        if (repository.existsById(PersonalInvitationBlockId(ownerMxid, organization, roleToken))) return
        repository.save(
            PersonalInvitationBlockEntity(
                id = PersonalInvitationBlockId(ownerMxid, organization, roleToken),
                organizationDisplay = organizationDisplay,
                roleDisplay = roleDisplay
            )
        )
    }

    @Transactional
    override fun removeRule(ownerMxid: String, organization: String, roleToken: String) {
        repository.deleteRule(ownerMxid, organization, roleToken)
    }

    @Transactional(readOnly = true)
    override fun getBlockedUsers(ownerMxid: Mxid): List<Mxid> =
        blockedUserRepository.findByIdOwnerMxidOrderByIdBlockedMxid(ownerMxid.value).map { Mxid(it.id.blockedMxid) }

    @Transactional
    override fun addBlockedUser(ownerMxid: Mxid, blockedMxid: Mxid) {
        require(blockedMxid.value.isNotBlank()) { "Blocked mxid must not be blank" }
        if (blockedUserRepository.existsById(PersonalBlockedUserId(ownerMxid.value, blockedMxid.value))) return
        blockedUserRepository.save(
            PersonalBlockedUserEntity(PersonalBlockedUserId(ownerMxid.value, blockedMxid.value))
        )
    }

    @Transactional
    override fun removeBlockedUser(ownerMxid: Mxid, blockedMxid: Mxid) {
        blockedUserRepository.deleteByIdOwnerMxidAndIdBlockedMxid(ownerMxid.value, blockedMxid.value)
    }

    private fun PersonalBlockedUserEntity.toRuleOrNull(): InvitationRule? {
        val blockedMxid = runCatching { Mxid(id.blockedMxid) }.getOrNull() ?: return null
        return InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Exact(blockedMxid),
            roleMatcher = RoleMatcher.Any,
            decision = Decision.DENY
        )
    }

    private fun PersonalInvitationBlockEntity.toRuleOrNull(): InvitationRule? {
        val token = id.roleToken
        if (token.isBlank() || !token.contains('|')) return null
        val code = token.substringAfterLast('|')
        if (code.isBlank()) return null
        return InvitationRule.UserPersonal(
            mxidMatcher = MxidMatcher.Any,
            roleMatcher = RoleMatcher.ByCode(
                system = token.substringBeforeLast('|'),
                code = code
            ),
            decision = Decision.DENY
        )
    }
}
