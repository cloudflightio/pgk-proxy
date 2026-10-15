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

import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCodeOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleRemoval
import io.cloudflight.pgk.proxy.search.api.port.ApplyRoleChangesPort
import io.cloudflight.pgk.proxy.search.api.port.RoleCatalogPort
import io.cloudflight.pgk.proxy.shared.events.PractitionerRolesLoadFailedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Application service for the "Rollen zuweisen" admin page.
 */
@Service
class PractitionerRoleAssignmentService(
    private val roleManagement: RoleManagementService,
    private val invitationRights: InvitationRightsService,
    private val roleCatalog: RoleCatalogPort,
    private val applyRoleChanges: ApplyRoleChangesPort,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional(readOnly = true)
    fun listPractitioners(query: String = ""): List<PractitionerRoleRow> {
        val q = query.trim()
        return invitationRights.listPractitioners()
            .filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
            .distinctBy { it.id }
            .map { row ->
                PractitionerRoleRow(
                    id = row.id,
                    name = row.name,
                    subline = row.subline,
                    initials = row.initials,
                    roles = roleChips(row.id)
                )
            }
    }

    fun findPractitioner(id: String): PractitionerRoleRow? =
        invitationRights.listPractitioners()
            .firstOrNull { it.id == id }
            ?.let { PractitionerRoleRow(it.id, it.name, it.subline, it.initials, emptyList()) }

    @Transactional(readOnly = true)
    fun roleOptions(
        practitionerId: String,
        query: String,
        checkedTokens: Set<String>?
    ): List<RoleAssignmentOption> {
        val effectiveChecked = checkedTokens ?: assignedTokens(practitionerId)
        return roleCatalog.loadEntries()
            .filterNot { it.retired }
            .filter { entry -> query.isBlank() || entry.display.contains(query, ignoreCase = true) }
            .sortedBy { it.display.lowercase() }
            .map { entry ->
                RoleAssignmentOption(
                    token = entry.token,
                    display = entry.display,
                    checked = entry.token in effectiveChecked
                )
            }
    }

    @Transactional(readOnly = true)
    fun findPrimaryOrganization(practitionerId: String): OrganizationOption? =
        roleManagement.findPrimaryOrganization(practitionerId)

    @Transactional(readOnly = true)
    fun searchOrganizations(query: String): List<OrganizationOption> =
        roleManagement.searchOrganizations(query)

    @Transactional
    fun applyAssignment(
        practitionerId: String,
        organizationId: String,
        roleTokens: List<String>
    ): String? {
        val submitted = roleTokens.distinct().toSet()
        val existing = assignedRoleCodes(practitionerId)
        val existingTokens = existing.mapTo(mutableSetOf()) { it.token }

        val additions = (submitted - existingTokens).toList()
        val removals = existing
            .filter { it.token !in submitted }
            .map { code ->
                RoleRemoval(
                    practitionerRoleId = code.removalKey.substringBefore('|'),
                    roleToken = code.token
                )
            }

        if (additions.isEmpty() && removals.isEmpty()) {
            return null
        }
        applyRoleChanges.applyRoleChanges(
            practitionerId = practitionerId,
            organizationId = organizationId,
            roleTokens = additions,
            removals = removals
        )
        return when {
            additions.isNotEmpty() && removals.isNotEmpty() -> "Rollen erfolgreich aktualisiert"
            additions.isNotEmpty() -> "Rollen erfolgreich zugewiesen"
            else -> "Rollen erfolgreich entfernt"
        }
    }


    @Transactional
    fun removeRole(practitionerId: String, roleToken: String): Boolean {
        val removals = assignedRoleCodes(practitionerId)
            .filter { it.token == roleToken }
            .map { code ->
                RoleRemoval(
                    practitionerRoleId = code.removalKey.substringBefore('|'),
                    roleToken = code.token
                )
            }
        if (removals.isEmpty()) return false
        applyRoleChanges.applyRoleChanges(practitionerId, "", emptyList(), removals)
        return true
    }

    private fun roleChips(practitionerId: String): List<PractitionerRoleRow.RoleChip> {
        val codes = runCatching { assignedRoleCodes(practitionerId) }
            .onFailure { eventPublisher.publishEvent(PractitionerRolesLoadFailedEvent(practitionerId, it)) }
            .getOrDefault(emptyList())
        return codes
            .distinctBy { it.token }
            .map { code -> PractitionerRoleRow.RoleChip(display = code.display, token = code.token) }
    }

    private fun assignedRoleCodes(practitionerId: String): List<RoleCodeOption> =
        roleManagement.findExistingRoles(practitionerId).flatMap { it.codes }

    private fun assignedTokens(practitionerId: String): Set<String> =
        assignedRoleCodes(practitionerId).mapTo(mutableSetOf()) { it.token }
}
