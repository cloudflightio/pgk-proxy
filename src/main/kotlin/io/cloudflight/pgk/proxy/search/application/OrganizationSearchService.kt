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

import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.OrganizationSuggestionMapper
import io.cloudflight.pgk.proxy.search.api.port.OrganizationSearchPort
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.port.FindOrganizationsByNamePort
import org.springframework.stereotype.Service

@Service
class OrganizationSearchService(
    private val findOrganizationsByName: FindOrganizationsByNamePort,
    private val organizationSuggestionMapper: OrganizationSuggestionMapper
) : OrganizationSearchPort {

    override fun searchOrganizations(query: String): List<Map<String, Any>> {
        if (query.isBlank()) return emptyList()

        val organizations = findOrganizationsByName.searchByName(query)
        val organizationsById = organizations.associateBy { it.id }

        return suggestableOrganizations(organizations, query, organizationsById)
            .map { organizationSuggestionMapper.toSuggestion(it, organizationsById) }
            .sortedBy { it["name"].toString() }
    }

    private fun suggestableOrganizations(
        organizations: List<RawOrganization>,
        query: String,
        organizationsById: Map<String, RawOrganization>
    ): List<RawOrganization> {
        val matching = organizations.filter { it.matches(query) }
        val matchingIds = matching.mapTo(mutableSetOf()) { it.id }
        val excludedIds = ancestorIds(matching, organizationsById) - matchingIds
        return organizations.filterNot { it.id in excludedIds }
    }

    private fun ancestorIds(
        matching: List<RawOrganization>,
        organizationsById: Map<String, RawOrganization>
    ): Set<String> {
        val ancestors = mutableSetOf<String>()
        matching.forEach { organization ->
            var parentId = organization.partOfReference
            while (parentId != null && ancestors.add(parentId)) {
                parentId = organizationsById[parentId]?.partOfReference
            }
        }
        return ancestors
    }

    private fun RawOrganization.matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) || alias.orEmpty().contains(query, ignoreCase = true)
}
