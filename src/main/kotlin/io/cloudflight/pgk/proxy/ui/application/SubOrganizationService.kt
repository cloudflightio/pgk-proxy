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

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.cloudflight.pgk.proxy.provisioning.port.CreateOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationByIdQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationTypeCatalogPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationsByParentQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.RemoveOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.UpdateOrganizationPort
import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import org.springframework.stereotype.Service

/**
 * Application service for the "Untergeordnete Organisationen" admin page.
 */
@Service
class SubOrganizationService(
    private val roleManagement: RoleManagementService,
    private val organizationTypeCatalog: OrganizationTypeCatalogPort,
    private val organizationQuery: OrganizationQueryPort,
    private val organizationByIdQuery: OrganizationByIdQueryPort,
    private val organizationsByParentQuery: OrganizationsByParentQueryPort,
    private val createOrganization: CreateOrganizationPort,
    private val updateOrganization: UpdateOrganizationPort,
    private val removeOrganization: RemoveOrganizationPort
) {

    /**
     * The directory is limited to three levels: a root organization, one sub-organization and
     * one further sub-organization.
     */
    private val maxOrgDepth = 2

    fun directoryTree(): List<OrganizationTreeNode> {
        val all = organizationQuery.getOrganizations()
        val ids = all.mapNotNullTo(mutableSetOf()) { it.id }
        val childrenById = all
            .filter { it.parentId != null && it.parentId in ids }
            .groupBy { it.parentId!! }
        val roots = all.filter { it.parentId == null || it.parentId !in ids }
        return roots.sortedBy { it.name.lowercase() }
            .mapNotNull { toNode(it, childrenById, depth = 0) }
    }

    fun findOrganization(id: String): OrganizationData? =
        organizationByIdQuery.getOrganization(orgId(id))

    fun parentName(parentId: String?): String? =
        parentId?.let { organizationByIdQuery.getOrganization(orgId(it))?.name }

    fun searchParentCandidates(query: String): List<OrganizationOption> {
        val depthById = organizationDepths(organizationQuery.getOrganizations())
        return roleManagement.searchOrganizations(query)
            .filter { depthById.getOrDefault(it.id.removePrefix("Organization/"), 0) < maxOrgDepth }
    }

    fun typeOptions(): List<OrganizationType> = organizationTypeCatalog.loadTypes()

    fun create(name: String, parentId: String, type: OrganizationType?): String {
        val parent = orgId(parentId)
        require(parent.isNotBlank()) { "Bitte wählen Sie eine übergeordnete Organisation." }
        require(organizationByIdQuery.getOrganization(parent) != null) {
            "Die übergeordnete Organisation existiert nicht."
        }
        require(depthOf(parent) < maxOrgDepth) {
            "Es können höchstens drei Ebenen angelegt werden: Wurzelorganisation, Unterorganisation, Unterorganisation."
        }
        return createOrganization.create(
            OrganizationData(name = name.trim(), parentId = parent, type = type)
        )
    }

    fun update(id: String, name: String, parentId: String, type: OrganizationType?) {
        val organizationId = orgId(id)
        require(organizationByIdQuery.getOrganization(organizationId) != null) { "Die Organisation existiert nicht." }
        val parent = orgId(parentId).takeIf { it.isNotBlank() }
        if (parent != null) {
            require(organizationByIdQuery.getOrganization(parent) != null) {
                "Die übergeordnete Organisation existiert nicht."
            }
            require(parent != organizationId && parent !in descendantIds(organizationId)) {
                "Die übergeordnete Organisation kann nicht die Organisation selbst oder eine ihrer Unterorganisationen sein."
            }
            require(depthOf(parent) + subtreeDepth(organizationId) < maxOrgDepth) {
                "Die Organisation kann nicht tiefer als drei Ebenen verschoben werden."
            }
        }
        updateOrganization.update(
            OrganizationData(id = organizationId, name = name.trim(), parentId = parent, type = type)
        )
    }

    fun delete(id: String) {
        val organizationId = orgId(id)
        val organization = organizationByIdQuery.getOrganization(organizationId)
            ?: throw IllegalArgumentException("Die Organisation existiert nicht.")
        val parent = organization.parentId.orEmpty()
        require(parent.isNotBlank() && organizationByIdQuery.getOrganization(parent) != null) {
            "Eine Wurzelorganisation kann nicht gelöscht werden."
        }
        require(organizationsByParentQuery.getOrganizations(organizationId).isEmpty()) {
            "Die Organisation hat noch Unterorganisationen."
        }
        removeOrganization.remove(organizationId)
    }

    private fun toNode(
        organization: OrganizationData,
        childrenById: Map<String, List<OrganizationData>>,
        depth: Int,
        visited: Set<String> = emptySet()
    ): OrganizationTreeNode? {
        val id = organization.id ?: return null
        if (id in visited) return null
        val children = if (depth >= maxOrgDepth) emptyList()
            else childrenById[id].orEmpty()
                .sortedBy { it.name.lowercase() }
                .mapNotNull { toNode(it, childrenById, depth + 1, visited + id) }
        return OrganizationTreeNode(
            id = id,
            name = organization.name,
            children = children
        )
    }

    private fun orgId(id: String): String = id.removePrefix("Organization/")

    private fun descendantIds(organizationId: String): Set<String> {
        val ids = mutableSetOf<String>()
        val queue = ArrayDeque(organizationsByParentQuery.getOrganizations(organizationId).mapNotNull { it.id })
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (ids.add(current)) {
                organizationsByParentQuery.getOrganizations(current).mapNotNullTo(queue) { it.id }
            }
        }
        return ids
    }

    private fun depthOf(id: String): Int {
        var depth = 0
        var current = id
        val seen = mutableSetOf<String>()
        while (current.isNotBlank() && seen.add(current)) {
            val parent = organizationByIdQuery.getOrganization(current)?.parentId.orEmpty()
            if (parent.isBlank()) break
            current = parent
            depth++
        }
        return depth
    }

    private fun subtreeDepth(organizationId: String, visited: Set<String> = emptySet()): Int {
        if (organizationId in visited) return 0
        val children = organizationsByParentQuery.getOrganizations(organizationId)
        if (children.isEmpty()) return 0
        return 1 + (children.mapNotNull { it.id }.maxOfOrNull { subtreeDepth(it, visited + organizationId) } ?: 0)
    }

    private fun organizationDepths(organizations: List<OrganizationData>): Map<String, Int> {
        val ids = organizations.mapNotNullTo(mutableSetOf()) { it.id }
        val byId = organizations.associateBy { it.id }
        val depthById = mutableMapOf<String, Int>()
        ids.forEach { id ->
            var depth = 0
            var current = id
            val seen = mutableSetOf<String>()
            while (current.isNotBlank() && seen.add(current)) {
                val parent = byId[current]?.parentId.orEmpty()
                if (parent.isBlank() || parent !in ids) break
                current = parent
                depth++
            }
            depthById[id] = depth
        }
        return depthById
    }
}
