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
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.BlockedRoleOption
import io.cloudflight.pgk.proxy.ui.application.BlockedUserRow
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.InvitationRightsRow
import io.cloudflight.pgk.proxy.ui.application.InvitationRightsService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * Admin page for invitation control (Einladungsrechte).
 *
 * Practitioner rows come from [InvitationRightsService], which reads them from the FHIR
 * provider directory via the invitation module. Toggling a switch persists the change to
 * the `pgk` database. The organization-wide role policy is a block list persisted in the
 * `pgk` database too; the "Blocked Roles" dialog replaces the full set per direction.
 */
@Controller
class InvitationRightsController(
    private val invitationRightsService: InvitationRightsService,
    private val auditLogger: AuditLogger
) {

    @GetMapping("/admin/invitation-rights")
    fun invitationRights(
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?,
    ): String {
        model.addAttribute("currentUser", CurrentUser.from(principal))
        val blockedRoles = loadBlockedRoles()
        model.addAttribute("receiveBlockedRoles", blockedRoles.receiveBlockedRoles)
        model.addAttribute("sendBlockedRoles", blockedRoles.sendBlockedRoles)
        model.addAttribute("practitioners", loadPractitioners())
        addBlockedUsers(model)
        return "invitation-rights"
    }

    /**
     * HTMX loads the "Blocked Roles" dialog for one direction; checkboxes are pre-checked
     * with the currently blocked roles. With a [q] query (typed into the dialog search box)
     * it returns only the [role-list] fragment so the filtered rows replace the list in
     * place; [blockedRoles] carries the checkboxes currently checked in the dialog so
     * filtering does not lose in-progress selections.
     */
    @GetMapping("/admin/invitation-rights/roles/{direction}/dialog")
    fun blockedRolesDialog(
        @PathVariable direction: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) blockedRoles: List<String>?,
        model: Model
    ): String {
        val dir = direction.toDirection()
        model.addAttribute("direction", direction)
        model.addAttribute("title", if (dir == InvitationDirection.INBOUND)
            "Gesperrte Rollen festlegen – Einladungen empfangen"
        else
            "Gesperrte Rollen festlegen – Einladungen senden")
        val query = q?.trim().orEmpty()
        model.addAttribute("q", query)
        model.addAttribute(
            "roles",
            loadDialogRoles(dir, query, blockedRoles?.toSet())
        )
        return if (q == null) "fragments/blocked-roles-dialog :: dialog" else "fragments/blocked-roles-dialog :: role-list"
    }

    /**
     * Renders the whole "Blocked Roles" panel (both rows) as a single-root fragment.
     */
    @GetMapping("/admin/invitation-rights/roles/panel")
    fun blockedRolesPanel(model: Model): String {
        addRoleLists(model)
        return "fragments/blocked-roles :: panel"
    }

    /**
     * HTMX saves the COMPLETE checked state of the "Blocked Roles" dialog for one direction.
     *
     * [blockedRoles] is the full set of blocked role tokens (unchecked boxes are not
     * submitted, so an empty/absent param clears the direction). On success the controller
     * returns the save-ok marker plus an out-of-band refresh of #blockedRolesPanel; on
     * failure an inline error is shown inside the dialog.
     */
    @PostMapping("/admin/invitation-rights/roles/{direction}")
    fun saveBlockedRoles(
        @PathVariable direction: String,
        @RequestParam(value = "blockedRoles", required = false) blockedRoles: List<String>?,
        model: Model
    ): String {
        model.addAttribute("direction", direction)
        return try {
            invitationRightsService.replaceBlockedRoles(direction.toDirection(), blockedRoles.orEmpty().toSet())
            auditLogger.info(
                AuditAction.InvitationRightsRolesSave,
                "Saved blocked roles ($direction): ${blockedRoles.orEmpty().sorted().joinToString(",")}"
            )
            model.addAttribute("message", "Gesperrte Rollen gespeichert.")
            addRoleLists(model)
            "fragments/blocked-roles :: save-ok"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRightsRolesSave, "Failed to save blocked roles ($direction)", e)
            "fragments/blocked-roles :: save-error"
        }
    }

    /**
     * HTMX toggles a single role on/off for one direction (click on a blocked-role chip's ✕).
     * Returns the whole "Blocked Roles" panel so the chips refresh with the saved state.
     */
    @PostMapping("/admin/invitation-rights/roles/{direction}/toggle")
    fun toggleBlockedRole(
        @PathVariable direction: String,
        @RequestParam roleToken: String,
        model: Model
    ): String {
        model.addAttribute("direction", direction)
        return try {
            invitationRightsService.toggleBlockedRole(direction.toDirection(), roleToken)
            auditLogger.info(AuditAction.InvitationRightsRolesToggle, "Toggled blocked role $roleToken ($direction)")
            addRoleLists(model)
            "fragments/blocked-roles :: panel"
        } catch (e: Exception) {
            auditLogger.error(
                AuditAction.InvitationRightsRolesToggle,
                "Failed to toggle blocked role $roleToken ($direction)", e
            )
            addRoleLists(model)
            "fragments/blocked-roles :: panel"
        }
    }

    /**
     * HTMX adds a Matrix mxid to the organization-wide block list (the "Blockierte Benutzer"
     * section). The admin can enter any mxid, e.g. a user harassing the practitioners. Returns
     * the whole panel so the chips and the fresh add-form are swapped in.
     */
    @PostMapping("/admin/invitation-rights/users/add")
    fun addBlockedUser(
        @RequestParam mxid: String,
        model: Model
    ): String {
        return try {
            invitationRightsService.setBlockedUser(mxid.trim(), true)
            auditLogger.info(AuditAction.InvitationRightsUserBlock, "Blocked user '${mxid.trim()}'")
            addBlockedUsers(model)
            "fragments/blocked-users :: panel"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRightsUserBlock, "Failed to block user '$mxid'", e)
            model.addAttribute("error", "Ungültige MXID: $mxid")
            addBlockedUsers(model)
            "fragments/blocked-users :: panel"
        }
    }

    /**
     * HTMX removes a Matrix mxid from the organization-wide block list. Returns the whole
     * panel so the chips and the add-form refresh with the saved state.
     */
    @PostMapping("/admin/invitation-rights/users/remove")
    fun removeBlockedUser(
        @RequestParam mxid: String,
        model: Model
    ): String {
        try {
            invitationRightsService.setBlockedUser(mxid.trim(), false)
            auditLogger.info(AuditAction.InvitationRightsUserUnblock, "Unblocked user '${mxid.trim()}'")
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRightsUserUnblock, "Failed to unblock user '$mxid'", e)
        }
        addBlockedUsers(model)
        return "fragments/blocked-users :: panel"
    }

    @GetMapping("/admin/invitation-rights/users/search")
    fun searchBlockedUsers(
        @RequestParam(required = false) mxid: String?,
        model: Model
    ): String {
        val query = mxid?.trim().orEmpty()
        model.addAttribute("q", query)
        model.addAttribute(
            "items",
            if (query.isBlank()) emptyList() else runCatching { invitationRightsService.searchUsers(query) }
                .onFailure {
                    auditLogger.error(AuditAction.SearchUsers, "User search failed for query '$query'", it)
                }
                .getOrDefault(emptyList())
        )
        return "fragments/blocked-users :: suggestions"
    }

    /**
     * HTMX calls this when a user's "receive" toggle is clicked.
     *
     * [value] is the NEW desired state (already inverted by Thymeleaf in the URL).
     * Returns only the switch fragment – HTMX swaps it in place. On failure the
     * switch is returned in its unchanged state together with an error notification.
     */
    @PostMapping("/admin/invitation-rights/{id}/receive/{value}")
    fun updateReceive(
        @PathVariable id: String,
        @PathVariable value: Boolean,   // Spring auto-converts "true"/"false" path segment
        model: Model
    ): String {
        model.addAttribute("practitionerId", id)
        return try {
            invitationRightsService.changeInvitationSetting(
                ChangeInvitationRights(id = id, receive = value, send = null)
            )
            auditLogger.info(
                AuditAction.InvitationRightsToggle,
                "Per-user toggle persisted for practitioner '$id': receive=$value"
            )
            model.addAttribute("switchValue", value)
            "fragments/switches :: receive-switch"
        } catch (e: Exception) {
            handleToggleFailure(e, id, value, "receive", model)
        }
    }

    @PostMapping("/admin/invitation-rights/{id}/send/{value}")
    fun updateSend(
        @PathVariable id: String,
        @PathVariable value: Boolean,
        model: Model
    ): String {
        model.addAttribute("practitionerId", id)
        return try {
            invitationRightsService.changeInvitationSetting(
                ChangeInvitationRights(id = id, send = value, receive = null)
            )
            auditLogger.info(
                AuditAction.InvitationRightsToggle,
                "Per-user toggle persisted for practitioner '$id': send=$value"
            )
            model.addAttribute("switchValue", value)
            "fragments/switches :: send-switch"
        } catch (e: Exception) {
            handleToggleFailure(e, id, value, "send", model)
        }
    }

    private fun loadPractitioners(): List<InvitationRightsRow> =
        runCatching { invitationRightsService.listPractitioners() }
            .onFailure { auditLogger.error(AuditAction.InvitationRightsPractitionersLoad, "Failed to load invitation-rights practitioners", it) }
            .getOrDefault(emptyList())

    private fun loadBlockedUsers(): List<BlockedUserRow> =
        runCatching { invitationRightsService.listBlockedUsers() }
            .onFailure { auditLogger.error(AuditAction.InvitationRightsBlockedUsersLoad, "Failed to load blocked users", it) }
            .getOrDefault(emptyList())

    private fun addBlockedUsers(model: Model) {
        model.addAttribute("blockedUsers", loadBlockedUsers())
    }

    private fun loadBlockedRoles(): OrgBlockedRoles =
        runCatching { invitationRightsService.getBlockedRoles() }
            .onFailure { auditLogger.error(AuditAction.InvitationRightsBlockedRolesLoad, "Failed to load blocked roles", it) }
            .getOrNull()
            ?: OrgBlockedRoles()

    private fun loadDialogRoles(
        direction: InvitationDirection,
        query: String,
        checkedTokens: Set<String>?
    ): List<BlockedRoleOption> =
        runCatching { invitationRightsService.getDialogRoles(direction, query, checkedTokens) }
            .onFailure { auditLogger.error(AuditAction.InvitationRightsDialogRolesLoad, "Failed to load roles for the blocked-roles dialog", it) }
            .getOrDefault(emptyList())

    private fun addRoleLists(model: Model) {
        val blockedRoles = loadBlockedRoles()
        model.addAttribute("receiveBlockedRoles", blockedRoles.receiveBlockedRoles)
        model.addAttribute("sendBlockedRoles", blockedRoles.sendBlockedRoles)
    }

    private fun String.toDirection(): InvitationDirection =
        runCatching { InvitationDirection.fromStorageValue(this) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown invitation-rights direction: $this") }

    /**
     * Renders the switch back in its unchanged state (the opposite of the requested
     * [requestedValue]) plus an out-of-band error notification, so a failed toggle
     * never wipes the table cell.
     */
    private fun handleToggleFailure(
        e: Exception,
        id: String,
        requestedValue: Boolean,
        switchType: String,
        model: Model
    ): String {
        auditLogger.error(AuditAction.InvitationRightsSettingUpdate, "Failed to update invitation setting for practitioner '$id'", e)
        model.addAttribute("switchValue", !requestedValue)
        model.addAttribute("switchType", switchType)
        model.addAttribute("error", "Änderung fehlgeschlagen")
        return "fragments/switches :: switch-error"
    }
}
