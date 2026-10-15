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

package io.cloudflight.pgk.proxy.search.application

import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.RoleSuggestionMapper
import io.cloudflight.pgk.proxy.search.api.port.ApplyRoleChangesPort
import io.cloudflight.pgk.proxy.search.api.port.CreatePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.port.ExistingRolesQueryPort
import io.cloudflight.pgk.proxy.search.api.port.FindRoleByTokenPort
import io.cloudflight.pgk.proxy.search.api.port.PrimaryOrganizationQueryPort
import io.cloudflight.pgk.proxy.search.api.port.RemovePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleRemoval
import io.cloudflight.pgk.proxy.search.api.port.RoleSearchPort
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.search.port.FindPractitionerRolesPort
import org.springframework.stereotype.Service

@Service
class RoleSearchService(
    private val findOrganizationById: FindOrganizationByIdPort,
    private val findPractitionerRoles: FindPractitionerRolesPort,
    private val createPractitionerRole: CreatePractitionerRolePort,
    private val removePractitionerRole: RemovePractitionerRolePort,
    private val roleCatalogService: RoleCatalogService,
    private val practitionerConsolidator: PractitionerConsolidator,
    private val roleSuggestionMapper: RoleSuggestionMapper
) : RoleSearchPort, ExistingRolesQueryPort, PrimaryOrganizationQueryPort, ApplyRoleChangesPort,
    FindRoleByTokenPort {

    override fun searchRoles(query: String): List<Map<String, Any>> {
        if (query.isBlank()) return emptyList()

        return roleCatalogService.search(query)
            .map(roleSuggestionMapper::toSuggestion)
            .sortedBy { it["codeDisplay"].toString() }
    }

    override fun findExistingRolesForPractitioner(
        practitionerId: String,
        rootOrganizationId: String
    ): List<Map<String, Any>> {
        val roles = findPractitionerRoles.findPractitionerRoles(practitionerId)
        val organizationsById = resolveOrganizations(roles)
        return roles
            .filter { role -> rootOrganizationId.isBlank() || rootOf(role, organizationsById) == rootOrganizationId }
            .map { roleToMap(it, organizationsById) }
    }

    override fun findPrimaryOrganizationForPractitioner(practitionerId: String): Map<String, Any>? {
        val organization = findPractitionerRoles.findPractitionerRoles(practitionerId)
            .firstNotNullOfOrNull { role -> resolveOrganization(role) }
            ?: return null

        return mapOf("id" to organization.id, "name" to organization.name)
    }

    override fun applyRoleChanges(
        practitionerId: String,
        organizationId: String,
        roleTokens: List<String>,
        removals: List<RoleRemoval>
    ) {
        require(practitionerId.isNotBlank()) { "HealthcareProvider must not be blank" }
        require(roleTokens.isNotEmpty() || removals.isNotEmpty()) { "No changes were found" }

        if (roleTokens.isNotEmpty()) {
            require(organizationId.isNotBlank()) { "Organization must be declared" }
        }

        val entries = roleTokens.map { token ->
            requireNotNull(roleCatalogService.findByToken(token)) { "unknown Role: $token" }
        }

        removals.forEach { removal ->
            removePractitionerRole.remove(removal.practitionerRoleId)
        }

        entries.forEach { entry ->
            createPractitionerRole.create(practitionerId, organizationId, entry)
        }
    }

    override fun findByToken(token: String): RoleCatalogEntry? = roleCatalogService.findByToken(token)

    private fun roleToMap(
        role: RawPractitionerRole,
        organizationsById: Map<String, RawOrganization>
    ): Map<String, Any> {
        val organization = role.organizationReference?.let { organizationsById[it] }
        val rootOrganization = organization
            ?.let { practitionerConsolidator.getOrganizationChain(it, organizationsById) { null } }
            ?.organizationChain
            ?.firstOrNull()
            ?.let { it.alias ?: it.name }
            .orEmpty()

        return mapOf(
            "practitionerRoleId" to role.id.orEmpty(),
            "organizationChain" to rootOrganization,
            "codes" to role.codes.map {
                mapOf(
                    "token" to RoleCatalogEntry.tokenOf(it.system, it.code ?: it.display),
                    "display" to it.display
                )
            }
        )
    }

    private fun rootOf(role: RawPractitionerRole, organizationsById: Map<String, RawOrganization>): String =
        role.organizationReference
            ?.let { organizationsById[it] }
            ?.let {
                practitionerConsolidator.getOrganizationChain(it, organizationsById, findOrganizationById::findById)
            }
            ?.root()
            ?.id
            .orEmpty()

    private fun resolveOrganizations(roles: List<RawPractitionerRole>): Map<String, RawOrganization> =
        roles.mapNotNull { it.organizationReference?.substringAfter("Organization/") }
            .distinct()
            .mapNotNull { id -> findOrganizationById.findById(id) }
            .associateBy { it.id }

    private fun resolveOrganization(role: RawPractitionerRole): RawOrganization? =
        role.organizationReference
            ?.substringAfter("Organization/")
            ?.let { findOrganizationById.findById(it) }
}
