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

import io.cloudflight.pgk.proxy.shared.events.OrganizationSearchFailedEvent
import io.cloudflight.pgk.proxy.shared.events.PractitionerListLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.PractitionerOrganizationResolveFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleAssignmentSaveFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleRemovalFailedEvent
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.PractitionerRoleAssignmentService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * Admin page "Rollen zuweisen": manages the roles of every directory practitioner.
 */
@Controller
@RequestMapping("/admin/practitioner-roles")
class PractitionerRolesController(
    private val service: PractitionerRoleAssignmentService,
    private val eventPublisher: ApplicationEventPublisher
) {

    @GetMapping
    fun page(
        @RequestParam(required = false, defaultValue = "") q: String,
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        model.addAttribute("currentUser", CurrentUser.from(principal))
        addRows(model, q)
        model.addAttribute("q", q.trim())
        return "practitioner-roles"
    }

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false, defaultValue = "") q: String,
        model: Model
    ): String {
        addRows(model, q)
        return "fragments/practitioner-roles :: practitioner-table"
    }

    @GetMapping("/organizations/search")
    fun searchOrganizations(
        @RequestParam(required = false, defaultValue = "") organizationQuery: String,
        model: Model
    ): String {
        model.addAttribute("q", organizationQuery)
        model.addAttribute("items", runCatching { service.searchOrganizations(organizationQuery) }
            .onFailure { eventPublisher.publishEvent(OrganizationSearchFailedEvent(organizationQuery, it)) }
            .getOrDefault(emptyList()))
        return "fragments/practitioner-roles :: organizationList"
    }


    @GetMapping("/organizations/select")
    fun selectOrganization(
        @RequestParam id: String,
        @RequestParam name: String,
        model: Model
    ): String {
        model.addAttribute("organizationId", id)
        model.addAttribute("organizationName", name)
        return "fragments/practitioner-roles :: organizationBox"
    }

    @GetMapping("/{id}/dialog")
    fun dialog(
        @PathVariable id: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(value = "roleTokens", required = false) roleTokens: List<String>?,
        model: Model
    ): String {
        val practitioner = service.findPractitioner(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekannter Gesundheitsdiensteanbieter: $id")
        model.addAttribute("practitionerId", id)
        model.addAttribute("dialogTitle", "Rollen zuweisen – ${practitioner.name}")
        model.addAttribute("q", q?.trim().orEmpty())
        addDialogRoles(model, practitionerId = id, q, roleTokens?.toSet())
        return if (q == null) "fragments/practitioner-roles :: dialog" else "fragments/practitioner-roles :: role-list"
    }

    @PostMapping("/{id}")
    fun save(
        @PathVariable id: String,
        @RequestParam(value = "roleTokens", required = false) roleTokens: List<String>?,
        @RequestParam(required = false, defaultValue = "") organizationId: String,
        model: Model
    ): String {
        return try {
            val message = service.applyAssignment(id, organizationId, roleTokens.orEmpty())
            model.addAttribute("message", message ?: "Keine Änderungen vorgenommen")
            addRows(model, "")
            "fragments/practitioner-roles :: save-ok"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message.orEmpty())
            "fragments/practitioner-roles :: save-error"
        } catch (e: Exception) {
            eventPublisher.publishEvent(RoleAssignmentSaveFailedEvent(id, e))
            model.addAttribute("error", "Speichern fehlgeschlagen.")
            "fragments/practitioner-roles :: save-error"
        }
    }

    @PostMapping("/{id}/remove-role")
    fun removeRole(
        @PathVariable id: String,
        @RequestParam roleToken: String,
        model: Model
    ): String {
        return try {
            val removed = service.removeRole(id, roleToken)
            model.addAttribute("message", if (removed) "Rolle entfernt" else "Rolle war nicht zugewiesen")
            addRows(model, "")
            "fragments/practitioner-roles :: remove-ok"
        } catch (e: Exception) {
            eventPublisher.publishEvent(RoleRemovalFailedEvent(id, roleToken, e))
            model.addAttribute("error", "Rolle konnte nicht entfernt werden.")
            addRows(model, "")
            "fragments/practitioner-roles :: remove-error"
        }
    }

    private fun addRows(model: Model, q: String) {
        val rows = runCatching { service.listPractitioners(q) }
            .onFailure { eventPublisher.publishEvent(PractitionerListLoadFailedEvent(it)) }
            .getOrDefault(emptyList())
        model.addAttribute("rows", rows)
    }

    private fun addDialogRoles(
        model: Model,
        practitionerId: String,
        q: String?,
        checkedTokens: Set<String>?
    ) {
        val query = q?.trim().orEmpty()
        val organization = runCatching { service.findPrimaryOrganization(practitionerId) }
            .onFailure { eventPublisher.publishEvent(PractitionerOrganizationResolveFailedEvent(practitionerId, it)) }
            .getOrNull()
        model.addAttribute("organizationId", organization?.id.orEmpty())
        model.addAttribute("organizationName", organization?.name.orEmpty())
        model.addAttribute(
            "roles",
            runCatching { service.roleOptions(practitionerId, query, checkedTokens) }
                .onFailure { eventPublisher.publishEvent(RoleCatalogLoadFailedEvent(it)) }
                .getOrDefault(emptyList())
        )
    }
}
