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

import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.CreateRoleRequest
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.RoleManagementService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping

@Controller
class IndexController(
    private val roleManagement: RoleManagementService,
    private val auditLogger: AuditLogger
) {

    @GetMapping("/")
    fun index(
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        val currentUser = CurrentUser.from(principal)
        model.addAttribute("currentUser", currentUser)
        model.addCurrentUserRoleForm(roleManagement, currentUser.subject)
        return "index"
    }

    @PostMapping("/api/roles/create")
    fun createRole(
        @ModelAttribute request: CreateRoleRequest,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        return try {
            val currentUser = CurrentUser.from(principal)
            requireOwnPractitioner(request.practitionerId, currentUser.subject)
            roleManagement.applyRoleChanges(request)
            auditLogger.info(
                AuditAction.RolesApply,
                "Applied role changes for practitioner '${request.practitionerId}': added=${request.roleIds} removed=${request.removedRoleIds}"
            )
            model.addAttribute("message", successMessage(request))
            model.addCurrentUserRoleForm(roleManagement, currentUser.subject)
            "fragments/action-response :: success"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message.orEmpty())
            "fragments/action-response :: error"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.RolesApply, "Failed to apply practitioner role changes", e)
            model.addAttribute("error", "Failed to apply practitioner role changes")
            "fragments/action-response :: error"
        }
    }

    private fun requireOwnPractitioner(submittedPractitionerId: String, userId: String) {
        if (userId.isBlank()) {
            throw IllegalArgumentException("You must be logged in to manage roles")
        }
        val ownPractitioner = roleManagement.findPractitionerByUserId(userId)
            ?: throw IllegalArgumentException("No practitioner found for the logged-in user")
        if (!ownPractitioner.id.equals(submittedPractitionerId, ignoreCase = true)) {
            throw IllegalArgumentException("Roles can only be changed for your own practitioner")
        }
    }

    private fun successMessage(request: CreateRoleRequest): String = when {
        request.roleIds.isEmpty() -> "Roles successfully removed"
        request.removedRoleIds.isEmpty() -> "Roles successfully added"
        else -> "Roles successfully updated"
    }
}
