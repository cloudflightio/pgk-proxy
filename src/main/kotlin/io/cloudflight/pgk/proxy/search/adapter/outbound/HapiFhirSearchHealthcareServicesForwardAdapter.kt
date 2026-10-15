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
import ca.uhn.fhir.rest.gclient.TokenClientParam
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.HealthcareServiceSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.OrganizationSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.TelecomMxidMapper
import io.cloudflight.pgk.proxy.search.domain.model.HealthcareServiceSearchBundle
import io.cloudflight.pgk.proxy.search.port.SearchHealthcareServicesByMxidPort
import io.cloudflight.pgk.proxy.search.port.SearchHealthcareServicesPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.searchTermsOf
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.Organization
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * FHIR-backed search of healthcare services and organizations that resolves reverse references
 * with explicit forward searches instead of `_revinclude` (which some FHIR servers, e.g. pineit,
 * cannot process).
 *
 * Active when `pgk.fhir.search-mode` is `forward` (default: `revinclude`).
 */
@Component
@ConditionalOnProperty(name = ["pgk.fhir.search-mode"], havingValue = "forward")
class HapiFhirSearchHealthcareServicesForwardAdapter(
    private val fhirClient: IGenericClient,
    private val healthcareServiceMapper: HealthcareServiceSearchMapper,
    private val organizationMapper: OrganizationSearchMapper,
    private val auditLogger: AuditLogger
) : SearchHealthcareServicesPort, SearchHealthcareServicesByMxidPort {

    init {
        auditLogger.info(
            "system", AuditAction.FhirSearchAdapterActive,
            "FHIR search adapter active: forward (reverse references resolved via forward searches, no _revinclude)"
        )
    }

    override fun searchHealthcareServices(name: String): HealthcareServiceSearchBundle {
        val terms = searchTermsOf(name)
        if (terms.isEmpty()) return HealthcareServiceSearchBundle(emptyList(), emptyList())
        val byName = searchHealthcareServicesByName(terms)
        val byOrganization = searchHealthcareServicesByOrganization(terms)

        val services = (byName.services + byOrganization.services).distinctBy { it.idElement.idPart }
        val organizations = (byName.organizations + byOrganization.organizations)
            .distinctBy { it.idElement.idPart }

        return HealthcareServiceSearchBundle(
            healthcareServices = services
                .map(healthcareServiceMapper::toDomain)
                .filter { it.mxid != null },
            organizations = organizations.map(organizationMapper::toDomain)
        )
    }

    private fun searchHealthcareServicesByName(terms: List<String>): HealthcareServiceSearchSet {
        val resources = fhirClient.search<Bundle>()
            .forResource(HealthcareService::class.java)
            .where(HealthcareService.NAME.contains().values(terms))
            .include(Include("HealthcareService:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource }

        return HealthcareServiceSearchSet(
            services = resources.filterIsInstance<HealthcareService>(),
            organizations = resources.filterIsInstance<Organization>()
        )
    }

    private fun searchHealthcareServicesByOrganization(terms: List<String>): HealthcareServiceSearchSet {
        val organizations = fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(Organization.NAME.contains().values(terms))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource as? Organization }
            .distinctBy { it.idElement.idPart }

        return HealthcareServiceSearchSet(
            services = servicesForOrganizations(organizations.map { it.idElement.idPart }),
            organizations = organizations + childOrganizations(organizations.map { it.idElement.idPart })
        )
    }

    /**
     * Searches healthcare services and their organizations whose telecom matches [mxid].
     */
    override fun search(mxid: Mxid): HealthcareServiceSearchBundle {
        val matrixUri = TelecomMxidMapper.MXID_INDICATOR + mxid.value.removePrefix("@")

        val resources = fhirClient.search<Bundle>()
            .forResource(HealthcareService::class.java)
            .where(TokenClientParam("telecom").exactly().systemAndCode(TelecomMxidMapper.TELECOM_SYSTEM, matrixUri))
            .include(Include("HealthcareService:organization"))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.mapNotNull { it.resource }

        val services = resources.filterIsInstance<HealthcareService>().distinctBy { it.idElement.idPart }
        val organizations = resources.filterIsInstance<Organization>().distinctBy { it.idElement.idPart }
        val missingOrganizations = services
            .mapNotNull { it.providedBy?.reference }
            .filter { reference -> organizations.none { it.idElement.idPart == reference.removePrefix("Organization/") } }

        return HealthcareServiceSearchBundle(
            healthcareServices = services.map { healthcareServiceMapper.toDomain(it) },
            organizations = (organizations + fetchOrganizations(missingOrganizations))
                .distinctBy { it.idElement.idPart }
                .map(organizationMapper::toDomain)
        )
    }

    private fun servicesForOrganizations(organizationIds: List<String>): List<HealthcareService> =
        organizationIds.flatMap { id ->
            fhirClient.search<Bundle>()
                .forResource(HealthcareService::class.java)
                .where(ReferenceClientParam("organization").hasId(fhirResourceId("Organization", id)))
                .returnBundle(Bundle::class.java)
                .execute()
                .entry.mapNotNull { it.resource as? HealthcareService }
        }.distinctBy { it.idElement.idPart }

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

    private data class HealthcareServiceSearchSet(
        val services: List<HealthcareService> = emptyList(),
        val organizations: List<Organization> = emptyList()
    )

    private fun fhirResourceId(type: String, id: String): String =
        if (id.startsWith("$type/")) id else "$type/$id"
}
