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

import io.cloudflight.pgk.proxy.search.api.domain.model.RoleOption
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.PersonalInvitationRulesService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Personal "Wer darf mich einladen" page: the logged-in user maintains the global blacklist of
 * roles that may not send them room invitations.
 *
 * Rules are keyed by the user's own mxid (resolved from the IDP subject); an empty list means
 * all-allowed. The add-rule dialog offers a role search with checkboxes; the panel is a flat
 * list of blocked roles. Lookup results are served as HTMX fragments.
 */
@Controller
@RequestMapping("/me/invitation-rules")
class PersonalInvitationRulesController(
    private val service: PersonalInvitationRulesService,
    private val auditLogger: AuditLogger
) {

    @GetMapping
    fun page(
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        addCurrentUser(model, principal)
        addOwnerMxid(model, principal)
        addRulesModel(model, principal)
        addBlockedUsersModel(model, principal)
        return "personal-invitation-rules"
    }

    /**
     * A fresh, empty add-rule dialog (loaded on every "Regel hinzufügen" click, so the dialog
     * never shows stale state).
     */
    @GetMapping("/dialog")
    fun dialog(model: Model): String {
        model.addAttribute("dialogTitle", "Regel hinzufügen")
        model.addAttribute("roles", emptyList<RoleOption>())
        return "fragments/personal-rules-dialog :: dialog"
    }

    /**
     * Role lookup for the add-rule dialog's roles field. [blockedRoles] carries the boxes
     * currently checked in the dialog (sent via `hx-include="closest form"`), so filtering
     * keeps in-progress selections.
     */
    @GetMapping("/search-roles")
    fun searchRoles(
        @RequestParam(required = false) q: String?,
        @RequestParam(value = "blockedRoles", required = false) blockedRoles: List<String>?,
        model: Model
    ): String {
        val query = q?.trim().orEmpty()
        val checkedTokens = blockedRoles.orEmpty().toSet()
        model.addAttribute("q", query)
        model.addAttribute("roles", if (query.isBlank()) emptyList() else service.searchRoles(query, checkedTokens))
        return "fragments/personal-rule-search :: roles"
    }

    /**
     * Saves the dialog: the checked roles are added to the global blacklist. Returns the save
     * marker plus an out-of-band refresh of the rules panel.
     */
    @PostMapping
    fun add(
        @RequestParam(value = "blockedRoles", required = false) blockedRoles: List<String>?,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        return try {
            val ownerMxid = requireOwnerMxid(principal)
            val roleTokens = blockedRoles.orEmpty().distinct()
            if (roleTokens.isNotEmpty()) {
                service.addRules(ownerMxid, roleTokens)
                auditLogger.info(
                    AuditAction.InvitationRulesSave,
                    "Added personal invitation rules for ${ownerMxid.value}: ${roleTokens.joinToString(",")}"
                )
            }
            model.addAttribute("message", "Regel hinzugefügt.")
            model.addAttribute("ownerMxid", ownerMxid.value)
            model.addAttribute("rules", service.listRules(ownerMxid))
            "fragments/personal-rules :: save-ok"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRulesSave, "Failed to save personal invitation rules", e)
            "fragments/personal-rules :: save-error"
        }
    }

    /**
     * Removes a single rule from the global blacklist; returns the refreshed rules panel as
     * the swap target.
     */
    @PostMapping("/remove")
    fun remove(
        @RequestParam roleToken: String,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        return try {
            val ownerMxid = requireOwnerMxid(principal)
            service.removeRule(ownerMxid, roleToken)
            auditLogger.info(AuditAction.InvitationRulesRemove, "Removed personal invitation rule for ${ownerMxid.value}: $roleToken")
            model.addAttribute("ownerMxid", ownerMxid.value)
            model.addAttribute("rules", service.listRules(ownerMxid))
            "fragments/personal-rules :: panel"
        } catch (e: Exception) {
            auditLogger.error(
                AuditAction.InvitationRulesRemove,
                "Failed to remove personal invitation rule ($roleToken)", e
            )
            model.addAttribute(
                "rules",
                runCatching { service.listRules(requireOwnerMxid(principal)) }
                    .getOrDefault(emptyList())
            )
            "fragments/personal-rules :: panel"
        }
    }

    @GetMapping("/search-users")
    fun searchUsers(
        @RequestParam(required = false) mxid: String?,
        model: Model
    ): String {
        val query = mxid?.trim().orEmpty()
        model.addAttribute("q", query)
        model.addAttribute(
            "items",
            if (query.isBlank()) emptyList() else runCatching { service.searchUsers(query) }
                .onFailure {
                    auditLogger.error(AuditAction.SearchUsers, "User search failed for query '$query'", it)
                }
                .getOrDefault(emptyList())
        )
        return "fragments/personal-blocked-users :: suggestions"
    }

    @PostMapping("/users/add")
    fun addBlockedUser(
        @RequestParam mxid: String,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        model.addAttribute("error", "")
        return try {
            val ownerMxid = requireOwnerMxid(principal)
            service.blockUser(ownerMxid, mxid)
            auditLogger.info(AuditAction.InvitationRulesUserBlock, "Blocked user '$mxid' for ${ownerMxid.value}")
            addBlockedUsersModel(model, principal)
            "fragments/personal-blocked-users :: panel"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRulesUserBlock, "Failed to block user '$mxid'", e)
            model.addAttribute("error", "Ungültige MXID: $mxid")
            addBlockedUsersModel(model, principal)
            "fragments/personal-blocked-users :: panel"
        }
    }

    @PostMapping("/users/remove")
    fun removeBlockedUser(
        @RequestParam mxid: String,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        try {
            val ownerMxid = requireOwnerMxid(principal)
            service.unblockUser(ownerMxid, mxid)
            auditLogger.info(AuditAction.InvitationRulesUserUnblock, "Unblocked user '$mxid' for ${ownerMxid.value}")
        } catch (e: Exception) {
            auditLogger.error(AuditAction.InvitationRulesUserUnblock, "Failed to unblock user '$mxid'", e)
        }
        model.addAttribute("error", "")
        addBlockedUsersModel(model, principal)
        return "fragments/personal-blocked-users :: panel"
    }

    private fun addCurrentUser(model: Model, principal: OidcUser?) {
        model.addAttribute("currentUser", CurrentUser.from(principal))
    }

    private fun addOwnerMxid(model: Model, principal: OidcUser?) {
        model.addAttribute(
            "ownerMxid",
            runCatching { requireOwnerMxid(principal) }.getOrNull()?.value.orEmpty()
        )
    }

    private fun addRulesModel(model: Model, principal: OidcUser?) {
        model.addAttribute(
            "rules",
            runCatching { requireOwnerMxid(principal) }
                .map { service.listRules(it) }
                .getOrDefault(emptyList())
        )
    }

    private fun addBlockedUsersModel(model: Model, principal: OidcUser?) {
        val owner = runCatching { requireOwnerMxid(principal) }.getOrNull()
        model.addAttribute(
            "blockedUsers",
            if (owner == null) emptyList() else runCatching { service.listBlockedUsers(owner) }
                .onFailure {
                    auditLogger.error(AuditAction.InvitationRulesBlockedUsersLoad, "Failed to load personal blocked users", it)
                }
                .getOrDefault(emptyList())
        )
    }

    private fun requireOwnerMxid(principal: OidcUser?): Mxid {
        val userId = CurrentUser.from(principal).subject
        require(userId.isNotBlank()) { "Sie müssen angemeldet sein" }
        return service.currentUserMxid(userId)
            ?: throw IllegalStateException("Kein Healthcareprovider für den angemeldeten Benutzer gefunden")
    }
}
