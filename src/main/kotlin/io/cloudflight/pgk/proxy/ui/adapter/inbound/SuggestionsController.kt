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

import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.RoleManagementService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class SuggestionsController(
    private val roleManagement: RoleManagementService,
    private val roleChipMapper: RoleChipMapper,
    private val auditLogger: AuditLogger
) {

    @GetMapping("/api/practitioners/search")
    fun searchPractitioners(@RequestParam q: String, model: Model): String {
        model.addAttribute("q", q)
        model.addAttribute("items", runCatching { roleManagement.searchPractitioners(q) }
            .onFailure {
                auditLogger.error(AuditAction.SearchPractitioners, "Practitioner search failed for query '$q'", it)
            }
            .getOrDefault(emptyList()))
        return "fragments/suggestions :: practitionerList"
    }

    @GetMapping("/api/organizations/search")
    fun searchOrganizations(@RequestParam q: String, model: Model): String {
        model.addAttribute("q", q)
        model.addAttribute("items", runCatching { roleManagement.searchOrganizations(q) }
            .onFailure {
                auditLogger.error(AuditAction.SearchOrganizations, "Organization search failed for query '$q'", it)
            }
            .getOrDefault(emptyList()))
        return "fragments/suggestions :: organizationList"
    }

    @GetMapping("/api/roles/search")
    fun searchRoles(
        @RequestParam q: String,
        model: Model
    ): String {
        model.addAttribute("q", q)
        model.addAttribute("items", runCatching { roleManagement.searchRoles(q) }
            .onFailure {
                auditLogger.error(AuditAction.SearchRoles, "Role search failed for query '$q'", it)
            }
            .getOrDefault(emptyList()))
        return "fragments/suggestions :: roleList"
    }

    @GetMapping("/api/practitioners/select")
    fun selectPractitioner(
        @RequestParam id: String,
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") organizationId: String,
        @RequestParam(required = false, defaultValue = "") organizationName: String,
        model: Model
    ): String {
        model.addAttribute("practitionerId", id)
        model.addAttribute("practitionerName", name)

        val organization = if (organizationId.isNotBlank()) {
            OrganizationOption(organizationId, organizationName)
        } else {
            runCatching { roleManagement.findPrimaryOrganization(id) }
                .onFailure {
                    auditLogger.error(
                        AuditAction.SearchPractitionerOrganization,
                        "Failed to resolve the organization of practitioner '$id'", it
                    )
                }
                .getOrNull()
        }
        model.addAttribute("organizationId", organization?.id.orEmpty())
        model.addAttribute("organizationName", organization?.name.orEmpty())
        model.addAttribute("organizationBoxOob", "true")

        addExistingRoles(model, id, emptyList(), organizationId)
        model.addAttribute("panelOob", "true")
        return "fragments/role-form :: practitionerSelection"
    }

    @GetMapping("/api/organizations/select")
    fun selectOrganization(@RequestParam id: String, @RequestParam name: String, model: Model): String {
        model.addAttribute("organizationId", id)
        model.addAttribute("organizationName", name)
        return "index :: organizationBox"
    }

    @GetMapping("/api/roles/select")
    fun selectRole(
        @RequestParam id: String,
        @RequestParam(required = false, defaultValue = "") roleIds: String,
        model: Model
    ): String {
        val current = parseCsv(roleIds)
        val updated = if (current.contains(id)) current else current + id
        model.addAttribute("selectedRoles", roleChipMapper.toChipOptions(updated))
        return "index :: roleBox"
    }

    @GetMapping("/api/roles/remove")
    fun removeRole(
        @RequestParam id: String,
        @RequestParam(required = false, defaultValue = "") roleIds: String,
        model: Model
    ): String {
        model.addAttribute("selectedRoles", roleChipMapper.toChipOptions(parseCsv(roleIds) - id))
        return "index :: roleBox"
    }

    @GetMapping("/api/roles/reset")
    fun resetForm(model: Model): String {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? OidcUser
        model.addCurrentUserRoleForm(roleManagement, CurrentUser.from(principal).subject)
        model.addAttribute("message", "Formular zurückgesetzt")
        return "fragments/action-response :: reset"
    }

    @GetMapping("/api/practitioner-roles/mark-removal")
    fun markRemoval(
        @RequestParam practitionerId: String,
        @RequestParam removalKey: String,
        @RequestParam(required = false, defaultValue = "") removedRoleIds: String,
        @RequestParam(required = false, defaultValue = "") organizationId: String,
        model: Model
    ): String {
        val staged = parseCsv(removedRoleIds)
        addExistingRoles(
            model, practitionerId, if (removalKey in staged) staged else staged + removalKey, organizationId
        )
        return "fragments/existing-roles :: panel"
    }

    @GetMapping("/api/practitioner-roles/undo-removal")
    fun undoRemoval(
        @RequestParam practitionerId: String,
        @RequestParam removalKey: String,
        @RequestParam(required = false, defaultValue = "") removedRoleIds: String,
        @RequestParam(required = false, defaultValue = "") organizationId: String,
        model: Model
    ): String {
        addExistingRoles(model, practitionerId, parseCsv(removedRoleIds) - removalKey, organizationId)
        return "fragments/existing-roles :: panel"
    }

    private fun addExistingRoles(
        model: Model,
        practitionerId: String,
        stagedRemovals: List<String>,
        rootOrganizationId: String = ""
    ) {
        model.addAttribute("practitionerId", practitionerId)
        model.addAttribute("rootOrganizationId", rootOrganizationId)
        model.addAttribute("removedRoleIds", stagedRemovals)
        model.addAttribute("existingRoles", runCatching {
            roleManagement.findExistingRoles(practitionerId, stagedRemovals, rootOrganizationId)
        }.onFailure {
            auditLogger.error(
                AuditAction.SearchPractitionerRoles,
                "Failed to load existing roles for practitioner '$practitionerId'", it
            )
        }.getOrDefault(emptyList()))
    }

    private fun parseCsv(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotBlank() }
}
