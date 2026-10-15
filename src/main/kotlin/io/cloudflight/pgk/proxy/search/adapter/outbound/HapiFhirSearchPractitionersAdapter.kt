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
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.OrganizationSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerRoleSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.TelecomMxidMapper
import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.port.*
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.infrastructure.fhir.HapiBundlePager
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.searchTermsOf
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.PractitionerRole
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * FHIR-backed search of practitioners, roles and organizations using `_revinclude`.
 *
 * Active when `pgk.fhir.search-mode` is `revinclude` (default). Servers that cannot process
 * `_revinclude` (e.g. pineit) must switch to `forward` and the corresponding forward adapter.
 */
@Component
@ConditionalOnProperty(name = ["pgk.fhir.search-mode"], havingValue = "revinclude", matchIfMissing = true)
class HapiFhirSearchPractitionersAdapter(
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
            "FHIR search adapter active: revinclude (requires server-side _revinclude support)"
        )
    }

    override fun search(name: String): PractitionerSearchBundle {
        val resources = searchByContent(searchTermsOf(name))

        val practitionerRoles = resources.filterIsInstance<PractitionerRole>()
            .distinctBy { it.idElement.idPart }
            .map(practitionerRoleMapper::toDomain)
        val organizations = (
            fetchOrganizations(practitionerRoles.mapNotNull { it.organizationReference }) +
                resources.filterIsInstance<Organization>()
                    .distinctBy { it.idElement.idPart }
                    .map(organizationMapper::toDomain)
            ).distinctBy { it.id }

        return PractitionerSearchBundle(
            practitioners = resources.filterIsInstance<Practitioner>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerMapper::toDomain),
            practitionerRoles = practitionerRoles,
            organizations = organizations
        )
    }

    /**
     * Searches practitioners and related roles/organizations whose telecom matches [mxid].
     */
    override fun search(mxid: Mxid): PractitionerSearchBundle {
        val resources = searchByMxid(mxid)

        return PractitionerSearchBundle(
            practitioners = resources.filterIsInstance<Practitioner>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerMapper::toDomain),
            practitionerRoles = resources.filterIsInstance<PractitionerRole>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerRoleMapper::toDomain),
            organizations = resources.filterIsInstance<Organization>()
                .distinctBy { it.idElement.idPart }
                .map(organizationMapper::toDomain)
        )
    }

    override fun searchByIdentifier(system: String, value: String): PractitionerSearchBundle {
        val resources = fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.IDENTIFIER.exactly().systemAndCode(system, value))
            .revInclude(Include("PractitionerRole:practitioner"))
            .include(Include("PractitionerRole:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .map { it.resource }

        return PractitionerSearchBundle(
            practitioners = resources.filterIsInstance<Practitioner>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerMapper::toDomain),
            practitionerRoles = resources.filterIsInstance<PractitionerRole>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerRoleMapper::toDomain),
            organizations = resources.filterIsInstance<Organization>()
                .distinctBy { it.idElement.idPart }
                .map(organizationMapper::toDomain)
        )
    }

    /**
     * Loads every practitioner provisioned by the proxy - identified by an identifier in the IDP
     * identifier system
     */
    override fun findProvisioned(): PractitionerSearchBundle {
        val resources = HapiBundlePager.allResources(
            fhirClient,
            fhirClient.search<Bundle>()
                .forResource(Practitioner::class.java)
                .where(Practitioner.IDENTIFIER.hasSystemWithAnyCode(properties.idp.identifierSystem))
                .revInclude(Include("PractitionerRole:practitioner"))
                .include(Include("PractitionerRole:organization"))
                .include(Include("Organization:partof").asRecursive())
                .count(HapiBundlePager.PAGE_SIZE)
                .returnBundle(Bundle::class.java)
                .execute()
        )

        return PractitionerSearchBundle(
            practitioners = resources.filterIsInstance<Practitioner>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerMapper::toDomain),
            practitionerRoles = resources.filterIsInstance<PractitionerRole>()
                .distinctBy { it.idElement.idPart }
                .map(practitionerRoleMapper::toDomain),
            organizations = resources.filterIsInstance<Organization>()
                .distinctBy { it.idElement.idPart }
                .map(organizationMapper::toDomain)
        )
    }

    private fun searchByMxid(mxid: Mxid): List<IBaseResource> {
        val matrixUri = TelecomMxidMapper.MXID_INDICATOR + mxid.value.removePrefix("@")

        return fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.TELECOM.exactly().systemAndCode(properties.idp.telecomSystem, matrixUri))
            .revInclude(Include("PractitionerRole:practitioner"))
            .include(Include("PractitionerRole:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .map { it.resource }
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

    override fun searchByName(name: String): List<RawOrganization> =
        fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(StringClientParam("name").contains().value(name))
            .include(Include("Organization:partof").asRecursive())
            .revInclude(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.map { it.resource }
            .filterIsInstance<Organization>()
            .distinctBy { it.idElement.idPart }
            .map(organizationMapper::toDomain)

    private fun searchByContent(terms: List<String>): List<IBaseResource> {
        if (terms.isEmpty()) return emptyList()

        val byName = fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.NAME.contains().values(terms))
            .revInclude(Include("PractitionerRole:practitioner"))
            .include(Include("PractitionerRole:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()

        // note(leo): this currently needs us to add a custom SearchParameter to the FHIR server we're consuming
        // if you're changing the `role-text` search param, check this commit and adapt the pgk fhir post init script
        val byRole = fhirClient.search<Bundle>()
            .forResource(PractitionerRole::class.java)
            .where(StringClientParam("role-text").contains().values(terms))
            .include(Include("PractitionerRole:practitioner"))
            .include(Include("PractitionerRole:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()

        val byOrganization = fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(Organization.NAME.contains().values(terms))
            .revInclude(Include("Organization:partof").asRecursive())
            .revInclude(Include("PractitionerRole:organization").asRecursive())
            .include(Include("PractitionerRole:practitioner").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()

        return (byName.entry.orEmpty() + byRole.entry.orEmpty() + byOrganization.entry.orEmpty()).map { it.resource }
    }

    private fun fetchOrganizations(references: List<String>): List<RawOrganization> {
        val ids = references.mapNotNull { it.removePrefix("Organization/").ifBlank { null } }
        if (ids.isEmpty()) return emptyList()
        return fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(TokenClientParam("_id").exactly().codes(ids))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.map { it.resource }
            .filterIsInstance<Organization>()
            .distinctBy { it.idElement.idPart }
            .map(organizationMapper::toDomain)
    }

    fun fhirResourceId(type: String, id: String): String =
        if (id.startsWith("$type/")) id else "$type/$id"
}
