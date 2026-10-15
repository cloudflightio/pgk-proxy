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

import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.*
import io.cloudflight.pgk.proxy.invitation.api.model.ChangeInvitationRights
import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.model.OrgBlockedRoles
import io.cloudflight.pgk.proxy.invitation.api.model.RolePermission
import io.cloudflight.pgk.proxy.invitation.api.port.ChangeInvitationSettingPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRoleQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesChangePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRolesTogglePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUserQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUsersChangePort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUsersQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsByMxidQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsQueryPort
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Component

/**
 * Invitation-rights persistence against the `pgk` Postgres database (see V1 migration).
 *
 * Practitioner/user rows: identity comes from the FHIR provider directory (via
 * [PractitionerDirectoryPort]), rights from `practitioner_invitation_rights`; an absent row
 * defaults to allowed. Role rows: `org_role_invitation_blocks`; an empty table means
 * nothing is blocked. The practitioner id is the bare FHIR idPart (e.g. `1`, not
 * `Practitioner/1`); the admin page and this adapter agree on that contract.
 */
@Component
class JpaInvitationRightsAdapter(
    private val directory: PractitionerDirectoryPort,
    private val practitionerByMxid: FindPractitionerByMxidPort,
    private val practitionerRights: PractitionerInvitationRightsRepository,
    private val roleBlocks: OrgRoleBlockRepository,
    private val blockedUsers: OrgBlockedUserRepository,
    private val auditLogger: AuditLogger
) : UserInvitationRightsQueryPort, UserInvitationRightsByMxidQueryPort, ChangeInvitationSettingPort,
    OrgBlockedRolesQueryPort, OrgBlockedRolesChangePort, OrgBlockedRolesTogglePort,
    OrgBlockedUsersQueryPort, OrgBlockedUserQueryPort, OrgBlockedUsersChangePort,
    OrgBlockedRoleQueryPort {

    override fun getPractitionerRights(): List<InvitationRightsEntry> {
        val entries = directory.list()
        val stored = practitionerRights.findAllById(entries.map { it.id })
            .associateBy { it.practitionerId }
        return entries.map { entry ->
            val rights = stored[entry.id]
            InvitationRightsEntry(
                id = entry.id,
                name = entry.name,
                mxid = entry.mxid,
                receive = rights?.receive ?: true,
                send = rights?.send ?: true
            )
        }
    }

    override fun getPractitionerRights(mxid: Mxid): InvitationRightsEntry? {
        val entry = practitionerByMxid.findByMxid(mxid) ?: return null
        val rights = practitionerRights.findById(entry.id).orElse(null)
        return InvitationRightsEntry(
            id = entry.id,
            name = entry.name,
            mxid = entry.mxid,
            receive = rights?.receive ?: true,
            send = rights?.send ?: true
        )
    }

    /**
     * Applies a per-practitioner toggle. An absent row means "allowed" for both directions,
     * so a row is only written when it actually blocks something, and a row whose settings
     * have all returned to "allowed" is deleted again (both `true`).
     */
    override fun changeInvitationSetting(setting: ChangeInvitationRights) {
        if (setting.receive == null && setting.send == null) {
            auditLogger.warn(
                "system", AuditAction.InvitationRightsChangeEmpty,
                "Ignoring empty invitation-rights change for practitioner '${setting.id}'"
            )
            return
        }
        val rights = practitionerRights.findById(setting.id).orElse(null)
        if (rights == null) {
            if (setting.receive == false || setting.send == false) {
                val newRow = PractitionerInvitationRightsEntity(setting.id)
                setting.receive?.let { newRow.receive = it }
                setting.send?.let { newRow.send = it }
                practitionerRights.save(newRow)
                auditLogger.info(
                    "system", AuditAction.InvitationRightsChangeCreated,
                    "Created invitation-rights row for practitioner '${setting.id}': receive=${newRow.receive}, send=${newRow.send}"
                )
            } else {
                auditLogger.info(
                    "system", AuditAction.InvitationRightsChangeAllowed,
                    "Toggle for practitioner '${setting.id}' without existing row kept at allowed (receive=${setting.receive}, send=${setting.send})"
                )
            }
            return
        }
        setting.receive?.let { rights.receive = it }
        setting.send?.let { rights.send = it }
        if (rights.receive && rights.send) {
            practitionerRights.delete(rights)
            auditLogger.info(
                "system", AuditAction.InvitationRightsChangeRemoved,
                "Removed invitation-rights row for practitioner '${setting.id}' (back to allowed: receive=true, send=true)"
            )
        } else {
            practitionerRights.save(rights)
            auditLogger.info(
                "system", AuditAction.InvitationRightsChangeUpdated,
                "Updated invitation-rights row for practitioner '${setting.id}': receive=${rights.receive}, send=${rights.send}"
            )
        }
    }

    override fun getBlockedRoles(): OrgBlockedRoles {
        val rows = roleBlocks.findAll().sortedBy { it.id.roleToken }
        return OrgBlockedRoles(
            receiveBlockedRoles = rows.filter { it.id.direction == InvitationDirection.INBOUND.storageValue }
                .map { it.toRolePermission() },
            sendBlockedRoles = rows.filter { it.id.direction == InvitationDirection.OUTBOUND.storageValue }
                .map { it.toRolePermission() }
        )
    }

    override fun isAnyRoleBlocked(roleTokens: Set<String>, direction: InvitationDirection): Boolean {
        if (roleTokens.isEmpty()) return false
        return roleBlocks.existsByDirectionAndRoleTokenIn(direction.storageValue, roleTokens)
    }

    override fun replaceBlockedRoles(direction: InvitationDirection, roleTokens: Set<String>) {
        val storage = direction.storageValue
        roleBlocks.deleteAllByDirection(storage)
        val entities = roleTokens.map { OrgRoleBlockEntity(OrgRoleBlockId(storage, it)) }
        if (entities.isNotEmpty()) {
            roleBlocks.saveAll(entities)
        }
    }

    override fun toggleBlockedRole(direction: InvitationDirection, roleToken: String) {
        val id = OrgRoleBlockId(direction.storageValue, roleToken)
        if (roleBlocks.existsById(id)) {
            roleBlocks.deleteById(id)
        } else {
            roleBlocks.save(OrgRoleBlockEntity(id))
        }
    }

    override fun getBlockedUsers(): Set<Mxid> =
        blockedUsers.findAll().mapTo(mutableSetOf()) { Mxid(it.mxid) }

    override fun isBlocked(mxid: Mxid): Boolean =
        blockedUsers.existsById(mxid.value)

    override fun setBlockedUser(mxid: Mxid, blocked: Boolean) {
        if (blocked) {
            if (!blockedUsers.existsById(mxid.value)) {
                blockedUsers.save(OrgBlockedUserEntity(mxid.value))
            }
        } else if (blockedUsers.existsById(mxid.value)) {
            blockedUsers.deleteById(mxid.value)
        }
    }

    private fun OrgRoleBlockEntity.toRolePermission(): RolePermission =
        RolePermission(token = id.roleToken, display = id.roleToken)
}
