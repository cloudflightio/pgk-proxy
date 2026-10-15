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

import io.cloudflight.pgk.proxy.search.api.port.ApplyRoleChangesPort
import io.cloudflight.pgk.proxy.search.api.domain.model.ExistingRoleRowOption
import io.cloudflight.pgk.proxy.search.api.port.CurrentUserPractitionerQueryPort
import io.cloudflight.pgk.proxy.search.api.port.ExistingRolesQueryPort
import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.search.api.port.OrganizationSearchPort
import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.search.api.port.PractitionerSearchPort
import io.cloudflight.pgk.proxy.search.api.port.PrimaryOrganizationQueryPort
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCodeOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleRemoval
import io.cloudflight.pgk.proxy.search.api.port.RoleSearchPort
import org.springframework.stereotype.Service

@Service
class RoleManagementService(
    private val practitionerSearch: PractitionerSearchPort,
    private val currentUserPractitioner: CurrentUserPractitionerQueryPort,
    private val organizationSearch: OrganizationSearchPort,
    private val roleSearch: RoleSearchPort,
    private val existingRoles: ExistingRolesQueryPort,
    private val primaryOrganization: PrimaryOrganizationQueryPort,
    private val applyRoleChanges: ApplyRoleChangesPort
) {

    fun searchPractitioners(query: String): List<PractitionerOption> {
        return practitionerSearch.searchPractitioners(query)
            .map {
                PractitionerOption(
                    id = it["id"].toString(),
                    name = it["name"].toString(),
                    trustLevel = it["trustLevel"].toString(),
                    mxid = it["mxid"].toString(),
                    organizationId = it["organizationId"].toString(),
                    organizationName = it["organizationName"].toString()
                )
            }
    }

    fun findPractitionerByUserId(userId: String): PractitionerOption? =
        currentUserPractitioner.findPractitionerByUserId(userId)?.let {
            PractitionerOption(
                id = it["id"].toString(),
                name = it["name"].toString(),
                trustLevel = it["trustLevel"].toString(),
                mxid = it["mxid"].toString(),
                organizationId = it["organizationId"].toString(),
                organizationName = it["organizationName"].toString()
            )
        }

    fun searchOrganizations(query: String): List<OrganizationOption> {
        return organizationSearch.searchOrganizations(query)
            .map { OrganizationOption(it["id"].toString(), it["name"].toString()) }
    }

    fun searchRoles(query: String): List<RoleOption> {
        return roleSearch.searchRoles(query)
            .map { RoleOption(it["id"].toString(), it["codeDisplay"].toString(), it["title"].toString()) }
    }

    fun findPrimaryOrganization(practitionerId: String): OrganizationOption? =
        primaryOrganization.findPrimaryOrganizationForPractitioner(practitionerId)
            ?.let { OrganizationOption(it["id"].toString(), it["name"].toString()) }

    fun applyRoleChanges(request: CreateRoleRequest) {
        applyRoleChanges.applyRoleChanges(
            practitionerId = request.practitionerId,
            organizationId = request.organizationId,
            roleTokens = request.roleIds,
            removals = request.removedRoleIds.mapNotNull(::parseRemoval)
        )
    }

    fun findExistingRoles(
        practitionerId: String,
        stagedRemovals: List<String> = emptyList(),
        rootOrganizationId: String = ""
    ): List<ExistingRoleRowOption> {
        val staged = stagedRemovals.toSet()
        return existingRoles.findExistingRolesForPractitioner(practitionerId, rootOrganizationId)
            .map { row -> toExistingRoleRowOption(row, staged) }
    }

    fun removalKey(practitionerRoleId: String, roleToken: String): String = "$practitionerRoleId|$roleToken"

    private fun parseRemoval(key: String): RoleRemoval? {
        val separator = key.indexOf('|')
        if (separator <= 0) return null
        return RoleRemoval(
            practitionerRoleId = key.substring(0, separator),
            roleToken = key.substring(separator + 1)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun toExistingRoleRowOption(row: Map<String, Any>, stagedRemovals: Set<String>): ExistingRoleRowOption {
        val practitionerRoleId = row["practitionerRoleId"].toString()
        val codes = (row["codes"] as List<Map<String, Any>>).map {
            val key = removalKey(practitionerRoleId, it["token"].toString())
            RoleCodeOption(
                token = it["token"].toString(),
                display = it["display"].toString(),
                markedForRemoval = key in stagedRemovals,
                removalKey = key
            )
        }
        return ExistingRoleRowOption(
            practitionerRoleId = practitionerRoleId,
            organizationChain = row["organizationChain"].toString(),
            codes = codes
        )
    }
}
