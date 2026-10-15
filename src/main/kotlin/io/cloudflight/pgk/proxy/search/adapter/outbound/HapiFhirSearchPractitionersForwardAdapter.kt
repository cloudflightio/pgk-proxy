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

package io.cloudflight.pgk.proxy.search.adapter.outbound

import ca.uhn.fhir.model.api.Include
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import ca.uhn.fhir.rest.gclient.StringClientParam
import ca.uhn.fhir.rest.gclient.TokenClientParam
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.infrastructure.fhir.HapiBundlePager
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.OrganizationSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerRoleSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.TelecomMxidMapper
import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.port.*
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.searchTermsOf
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.PractitionerRole
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * FHIR-backed search of practitioners, roles and organizations that resolves reverse references
 * with explicit forward searches instead of `_revinclude` (which some FHIR servers, e.g. pineit,
 * cannot process).
 *
 * Active when `pgk.fhir.search-mode` is `forward` (default: `revinclude`).
 */
@Component
@ConditionalOnProperty(name = ["pgk.fhir.search-mode"], havingValue = "forward")
class HapiFhirSearchPractitionersForwardAdapter(
    private val fhirClient: IGenericClient,
    private val practitionerMapper: PractitionerSearchMapper,
    private val practitionerRoleMapper: PractitionerRoleSearchMapper,
    private val organizationMapper: OrganizationSearchMapper,
    private val properties: ProxyProperties,
    private val auditLogger: AuditLogger
) : SearchPractitionersByNamePort, SearchPractitionersByMxidPort, SearchPractitionersByIdentifierPort,
    FindOrganizationByIdPort, FindPractitionerRolesPort, FindOrganizationsByNamePort, FindProvisionedPractitionersPort {

    init {
        auditLogger.info(
            "system", AuditAction.FhirSearchAdapterActive,
            "FHIR search adapter active: forward (reverse references resolved via forward searches, no _revinclude)"
        )
    }

    override fun search(name: String): PractitionerSearchBundle {
        val terms = searchTermsOf(name)
        if (terms.isEmpty()) return toBundle(emptyList(), emptyList(), emptyList())
        val byName = searchPractitionersByName(terms)
        val byRole = searchRolesByRoleText(terms)
        val byOrganization = searchOrganizationsByName(terms)

        val practitionersByRole = practitionersForRefs(byRole.mapNotNull { it.practitioner?.reference })
        val rolesByOrganization = rolesForOrganizations(byOrganization.map { it.idElement.idPart })
        val practitionersByOrganization =
            practitionersForRefs(rolesByOrganization.mapNotNull { it.practitioner?.reference })

        val practitioners = (byName + practitionersByRole + practitionersByOrganization)
            .distinctBy { it.idElement.idPart }
        val roles = (byRole + rolesByOrganization + rolesForPractitioners(practitioners.map { it.idElement.idPart }))
            .distinctBy { it.idElement.idPart }
        val organizations = fetchOrganizations(roles.mapNotNull { it.organization?.reference })

        return toBundle(practitioners, roles, organizations)
    }

    /**
     * Searches practitioners and related roles/organizations whose telecom matches [mxid].
     */
    override fun search(mxid: Mxid): PractitionerSearchBundle {
        val matrixUri = TelecomMxidMapper.MXID_INDICATOR + mxid.value.removePrefix("@")

        val practitioners = fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.TELECOM.exactly().systemAndCode(properties.idp.telecomSystem, matrixUri))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Practitioner }
            .distinctBy { it.idElement.idPart }

        return resolveFor(practitioners)
    }

    override fun searchByIdentifier(system: String, value: String): PractitionerSearchBundle {
        val practitioners = fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.IDENTIFIER.exactly().systemAndCode(system, value))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Practitioner }
            .distinctBy { it.idElement.idPart }

        return resolveFor(practitioners)
    }

    /**
     * Loads every practitioner provisioned by the proxy - identified by an identifier in the IDP
     * identifier system
     */
    override fun findProvisioned(): PractitionerSearchBundle {
        val practitioners = HapiBundlePager.allResources(
            fhirClient,
            fhirClient.search<Bundle>()
                .forResource(Practitioner::class.java)
                .where(Practitioner.IDENTIFIER.hasSystemWithAnyCode(properties.idp.identifierSystem))
                .count(HapiBundlePager.PAGE_SIZE)
                .returnBundle(Bundle::class.java)
                .execute()
        ).filterIsInstance<Practitioner>()
            .distinctBy { it.idElement.idPart }

        return resolveFor(practitioners)
    }

    override fun findById(id: String): RawOrganization? = runCatching {
        val organization = fhirClient.read()
            .resource(Organization::class.java)
            .withId(id)
            .execute()
        organizationMapper.toDomain(organization)
    }.getOrNull()

    override fun findPractitionerRoles(practitionerId: String): List<RawPractitionerRole> =
        fhirClient.search<Bundle>()
            .forResource(PractitionerRole::class.java)
            .where(ReferenceClientParam("practitioner").hasId(fhirResourceId("Practitioner", practitionerId)))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.map { it.resource }
            .filterIsInstance<PractitionerRole>()
            .map(practitionerRoleMapper::toDomain)

    override fun searchByName(name: String): List<RawOrganization> {
        val organizations = fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(StringClientParam("name").contains().value(name))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Organization }
            .distinctBy { it.idElement.idPart }

        return (organizations + childOrganizations(organizations.map { it.idElement.idPart }))
            .distinctBy { it.idElement.idPart }
            .map(organizationMapper::toDomain)
    }

    private fun resolveFor(practitioners: List<Practitioner>): PractitionerSearchBundle {
        val roles = rolesForPractitioners(practitioners.map { it.idElement.idPart })
        val organizations = fetchOrganizations(roles.mapNotNull { it.organization?.reference })
        return toBundle(practitioners, roles, organizations)
    }

    private fun toBundle(
        practitioners: List<Practitioner>,
        roles: List<PractitionerRole>,
        organizations: List<Organization>
    ) = PractitionerSearchBundle(
        practitioners = practitioners.distinctBy { it.idElement.idPart }.map(practitionerMapper::toDomain),
        practitionerRoles = roles.distinctBy { it.idElement.idPart }.map(practitionerRoleMapper::toDomain),
        organizations = organizations.distinctBy { it.idElement.idPart }.map(organizationMapper::toDomain)
    )

    private fun searchPractitionersByName(terms: List<String>): List<Practitioner> =
        fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.NAME.contains().values(terms))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Practitioner }
            .distinctBy { it.idElement.idPart }

    private fun searchRolesByRoleText(terms: List<String>): List<PractitionerRole> =
        fhirClient.search<Bundle>()
            .forResource(PractitionerRole::class.java)
            .where(StringClientParam("role-text").contains().values(terms))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? PractitionerRole }
            .distinctBy { it.idElement.idPart }

    private fun searchOrganizationsByName(terms: List<String>): List<Organization> =
        fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(Organization.NAME.contains().values(terms))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Organization }
            .distinctBy { it.idElement.idPart }

    private fun rolesForPractitioners(practitionerIds: List<String>): List<PractitionerRole> =
        practitionerIds.flatMap { id ->
            fhirClient.search<Bundle>()
                .forResource(PractitionerRole::class.java)
                .where(ReferenceClientParam("practitioner").hasId(fhirResourceId("Practitioner", id)))
                .returnBundle(Bundle::class.java)
                .execute()
                .entry.mapNotNull { it.resource as? PractitionerRole }
        }.distinctBy { it.idElement.idPart }

    private fun rolesForOrganizations(organizationIds: List<String>): List<PractitionerRole> =
        organizationIds.flatMap { id ->
            fhirClient.search<Bundle>()
                .forResource(PractitionerRole::class.java)
                .where(ReferenceClientParam("organization").hasId(fhirResourceId("Organization", id)))
                .returnBundle(Bundle::class.java)
                .execute()
                .entry.mapNotNull { it.resource as? PractitionerRole }
        }.distinctBy { it.idElement.idPart }

    private fun practitionersForRefs(references: List<String>): List<Practitioner> {
        val ids = references.mapNotNull { reference ->
            if (reference.startsWith("Practitioner/")) reference.removePrefix("Practitioner/") else null
        }
        if (ids.isEmpty()) return emptyList()
        return fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(TokenClientParam("_id").exactly().codes(ids))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Practitioner }
            .distinctBy { it.idElement.idPart }
    }

    private fun childOrganizations(parentIds: List<String>): List<Organization> =
        parentIds.flatMap { id ->
            fhirClient.search<Bundle>()
                .forResource(Organization::class.java)
                .where(ReferenceClientParam("partof").hasId(fhirResourceId("Organization", id)))
                .returnBundle(Bundle::class.java)
                .execute()
                .entry.mapNotNull { it.resource as? Organization }
        }.distinctBy { it.idElement.idPart }

    private fun fetchOrganizations(references: List<String>): List<Organization> {
        val ids = references.mapNotNull { reference ->
            if (reference.startsWith("Organization/")) reference.removePrefix("Organization/") else null
        }
        if (ids.isEmpty()) return emptyList()
        return fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(TokenClientParam("_id").exactly().codes(ids))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Organization }
            .distinctBy { it.idElement.idPart }
    }

    private fun fhirResourceId(type: String, id: String): String =
        if (id.startsWith("$type/")) id else "$type/$id"
}
