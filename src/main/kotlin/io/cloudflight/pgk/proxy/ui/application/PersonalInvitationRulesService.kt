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

import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalBlockedUsersRemovePort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesAddPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.PersonalInvitationRulesRemovePort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsQueryPort
import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleOption
import io.cloudflight.pgk.proxy.search.api.port.CurrentUserMxidQueryPort
import io.cloudflight.pgk.proxy.search.api.port.FindRoleByTokenPort
import io.cloudflight.pgk.proxy.search.api.port.PractitionerSearchPort
import io.cloudflight.pgk.proxy.search.api.port.RoleSearchPort
import io.cloudflight.pgk.proxy.shared.Mxid
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for the personal invitation-rules page ("Wer darf mich einladen").
 *
 * Resolves the logged-in user's own mxid (owner of the blacklist) and lists/edits the stored
 * rules. The blacklist is a global role blacklist only: every stored rule blocks one role
 * across all organizations, an empty list means all-allowed. The lookups reuse the search
 * module's role catalog search.
 */
@Service
class PersonalInvitationRulesService(
    private val currentUserMxidQuery: CurrentUserMxidQueryPort,
    private val personalRulesQuery: PersonalInvitationRulesQueryPort,
    private val personalRulesAdd: PersonalInvitationRulesAddPort,
    private val personalRulesRemove: PersonalInvitationRulesRemovePort,
    private val blockedUsersQuery: PersonalBlockedUsersQueryPort,
    private val blockedUsersAdd: PersonalBlockedUsersAddPort,
    private val blockedUsersRemove: PersonalBlockedUsersRemovePort,
    private val userInvitationRights: UserInvitationRightsQueryPort,
    private val roleSearch: RoleSearchPort,
    private val findRoleByToken: FindRoleByTokenPort,
    private val practitionerSearch: PractitionerSearchPort
) {

    /** @return the mxid of the practitioner provisioned for the IDP [userId], or null */
    fun currentUserMxid(userId: String): Mxid? = currentUserMxidQuery.findMxidByUserId(userId)

    @Transactional(readOnly = true)
    fun listRules(ownerMxid: Mxid): List<PersonalInvitationRuleView> =
        personalRulesQuery.getRules(ownerMxid.value)
            .map { rule ->
                PersonalInvitationRuleView(
                    organization = rule.organization,
                    organizationDisplay = rule.organizationDisplay.ifBlank { rule.organization },
                    roleToken = rule.roleToken,
                    roleDisplay = rule.roleDisplay.ifBlank { rule.roleToken }
                )
            }
            .sortedBy { it.roleDisplay.lowercase() }

    /**
     * Role lookup for the add-rule dialog's roles field. [checkedTokens] carries the boxes
     * currently checked in the dialog (sent via `hx-include="closest form"`), so filtering
     * keeps in-progress selections: tokens that are checked but no longer match are resolved
     * individually and prepended, so they stay visible (and checked) until unselected.
     */
    fun searchRoles(query: String, checkedTokens: Set<String>): List<RoleOption> {
        val matches = roleSearch.searchRoles(query)
            .map {
                RoleOption(
                    id = it["id"].toString(),
                    codeDisplay = it["codeDisplay"].toString(),
                    title = it["title"].toString(),
                    blocked = it["id"].toString() in checkedTokens
                )
            }
        val matchedTokens = matches.mapTo(mutableSetOf()) { it.id }
        val unmatchedChecked = checkedTokens
            .filterNot { it in matchedTokens }
            .map { token ->
                findRoleByToken.findByToken(token)?.let { entry ->
                    RoleOption(token, entry.display, entry.title.orEmpty(), blocked = true)
                } ?: RoleOption(token, token, "", blocked = true)
            }
        return unmatchedChecked + matches
    }

    @Transactional
    fun addRules(ownerMxid: Mxid, roleTokens: List<String>) {
        roleTokens.filter { it.isNotBlank() }
            .distinct()
            .forEach { token ->
                personalRulesAdd.addRule(
                    ownerMxid = ownerMxid.value,
                    organization = "",
                    roleToken = token,
                    organizationDisplay = "",
                    roleDisplay = findRoleByToken.findByToken(token)?.display.orEmpty()
                )
            }
    }

    @Transactional
    fun removeRule(ownerMxid: Mxid, roleToken: String) {
        personalRulesRemove.removeRule(ownerMxid.value, "", roleToken)
    }

    @Transactional(readOnly = true)
    fun listBlockedUsers(ownerMxid: Mxid): List<BlockedUserRow> {
        val namesByIdp = userInvitationRights.getPractitionerRights()
            .filter { it.mxid != null }
            .associate { it.mxid!!.value to it.name }
        return blockedUsersQuery.getBlockedUsers(ownerMxid)
            .map { mxid ->
                BlockedUserRow(
                    mxid = mxid.value,
                    name = namesByIdp[mxid.value] ?: mxid.value
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    @Transactional
    fun blockUser(ownerMxid: Mxid, blockedMxid: String) {
        blockedUsersAdd.addBlockedUser(ownerMxid, Mxid(blockedMxid.trim()))
    }

    @Transactional
    fun unblockUser(ownerMxid: Mxid, blockedMxid: String) {
        blockedUsersRemove.removeBlockedUser(ownerMxid, Mxid(blockedMxid.trim()))
    }

    fun searchUsers(query: String): List<PractitionerOption> =
        practitionerSearch.searchPractitioners(query)
            .map {
                PractitionerOption(
                    id = it["id"].toString(),
                    name = it["name"].toString(),
                    trustLevel = it["trustLevel"].toString(),
                    mxid = it["mxid"].toString()
                )
            }
            .filter { it.mxid.isNotBlank() }
}
