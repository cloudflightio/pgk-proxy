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

import io.cloudflight.pgk.proxy.invitation.api.model.ChangeInvitationRights
import io.cloudflight.pgk.proxy.invitation.api.model.OrgBlockedRoles
import io.cloudflight.pgk.proxy.invitation.api.model.RolePermission
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for the invitation-rights admin page.
 *
 * Sits between the inbound controller and the outbound [UserInvitationRightsQueryPort]
 * (owned by the invitation module), mapping invitation domain entries into the
 * view model. Receive/send values come from the `pgk` database (absent row = allowed).
 * Organization-wide role policy is a block list (empty = nothing blocked).
 */
@Service
class InvitationRightsService(
    private val invitationRights: UserInvitationRightsQueryPort,
    private val changeInvitationSettingPort: ChangeInvitationSettingPort,
    private val blockedRolesQuery: OrgBlockedRolesQueryPort,
    private val blockedRolesChange: OrgBlockedRolesChangePort,
    private val blockedRolesToggle: OrgBlockedRolesTogglePort,
    private val blockedUsersQuery: OrgBlockedUsersQueryPort,
    private val blockedUsersChange: OrgBlockedUsersChangePort,
    private val roleCatalog: RoleCatalogPort,
    private val findRoleByToken: FindRoleByTokenPort,
    private val practitionerSearch: PractitionerSearchPort
) {

    /**
     * Practitioner rows on the invitation-rights page: only practitioners that also exist
     * in the IDP (i.e. carry a Matrix mxid) can be administered. The display name is the
     * one already implemented for directory entries.
     */
    @Transactional(readOnly = true)
    fun listPractitioners(): List<InvitationRightsRow> =
        invitationRights.getPractitionerRights()
            .filter { it.mxid != null }
            .map { entry ->
                InvitationRightsRow(
                    id = entry.id,
                    name = entry.name,
                    subline = entry.mxid?.value.orEmpty(),
                    initials = initialsOf(entry.name),
                    receive = entry.receive,
                    send = entry.send
                )
            }

    /**
     * User rows for the "Blockierte Benutzer" section: only the manually blocked mxids from
     * the organization-wide block list (e.g. harassing users). A known IDP user is shown by
     * its display name; an unknown mxid is shown as-is.
     */
    @Transactional(readOnly = true)
    fun listBlockedUsers(): List<BlockedUserRow> {
        val namesByIdp = invitationRights.getPractitionerRights()
            .filter { it.mxid != null }
            .associate { it.mxid!!.value to it.name }
        return blockedUsersQuery.getBlockedUsers()
            .map { mxid ->
                BlockedUserRow(
                    mxid = mxid.value,
                    name = namesByIdp[mxid.value] ?: mxid.value
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    @Transactional
    fun setBlockedUser(mxid: String, blocked: Boolean) {
        blockedUsersChange.setBlockedUser(Mxid(mxid), blocked)
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

    @Transactional
    fun changeInvitationSetting(setting: ChangeInvitationRights) {
        changeInvitationSettingPort.changeInvitationSetting(setting)
    }

    @Transactional(readOnly = true)
    fun getBlockedRoles(): OrgBlockedRoles {
        val blocked = blockedRolesQuery.getBlockedRoles()
        return OrgBlockedRoles(
            receiveBlockedRoles = blocked.receiveBlockedRoles.map { it.withDisplay() },
            sendBlockedRoles = blocked.sendBlockedRoles.map { it.withDisplay() }
        )
    }

    @Transactional
    fun replaceBlockedRoles(direction: InvitationDirection, roleTokens: Set<String>) {
        blockedRolesChange.replaceBlockedRoles(direction, roleTokens)
    }

    @Transactional
    fun toggleBlockedRole(direction: InvitationDirection, roleToken: String) {
        blockedRolesToggle.toggleBlockedRole(direction, roleToken)
    }

    /**
     * Full role list for the "Blocked Roles" dialog: every non-retired catalog role
     * with its current blocked state for [direction].
     */
    fun getDialogRoles(direction: InvitationDirection): List<BlockedRoleOption> {
        val blockedTokens = getBlockedRoles().tokensFor(direction)
        return roleCatalog.loadEntries()
            .filterNot { it.retired }
            .sortedBy { it.display.lowercase() }
            .map { entry ->
                BlockedRoleOption(
                    token = entry.token,
                    display = entry.display,
                    blocked = entry.token in blockedTokens
                )
            }
    }

    /**
     * Dialog roles filtered by [query] (case-insensitive on display/token). [checkedTokens]
     * carries the checkboxes currently checked in the dialog (null on first open → the
     * stored block list is used), so filtering keeps in-progress selections.
     */
    fun getDialogRoles(
        direction: InvitationDirection,
        query: String,
        checkedTokens: Set<String>?
    ): List<BlockedRoleOption> {
        val allRoles = getDialogRoles(direction)
        val effectiveChecked = checkedTokens
            ?: allRoles.filter { it.blocked }.mapTo(mutableSetOf()) { it.token }
        return allRoles
            .filter { role -> query.isBlank() || role.matches(query) }
            .map { it.copy(blocked = it.token in effectiveChecked) }
    }

    private fun BlockedRoleOption.matches(query: String): Boolean =
        display.contains(query, ignoreCase = true) || token.contains(query, ignoreCase = true)

    private fun OrgBlockedRoles.tokensFor(direction: InvitationDirection): Set<String> =
        when (direction) {
            InvitationDirection.INBOUND -> receiveBlockedRoles.mapTo(mutableSetOf()) { it.token }
            InvitationDirection.OUTBOUND -> sendBlockedRoles.mapTo(mutableSetOf()) { it.token }
        }

    private fun RolePermission.withDisplay(): RolePermission = copy(display = displayFor(token))

    private fun displayFor(token: String): String =
        findRoleByToken.findByToken(token)?.display ?: RoleCatalogEntry.splitToken(token).second

    private fun initialsOf(name: String): String =
        name.split(" ")
            .filter { it.isNotBlank() }
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .uppercase()
}
