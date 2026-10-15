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
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.Organization
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * FHIR-backed search of healthcare services and organizations using `_revinclude`.
 *
 * Active when `pgk.fhir.search-mode` is `revinclude` (default). Servers that cannot process
 * `_revinclude` (e.g. pineit) must switch to `forward` and the corresponding forward adapter.
 */
@Component
@ConditionalOnProperty(name = ["pgk.fhir.search-mode"], havingValue = "revinclude", matchIfMissing = true)
class HapiFhirSearchHealthcareServicesAdapter(
    private val fhirClient: IGenericClient,
    private val healthcareServiceMapper: HealthcareServiceSearchMapper,
    private val organizationMapper: OrganizationSearchMapper,
    private val auditLogger: AuditLogger
) : SearchHealthcareServicesPort, SearchHealthcareServicesByMxidPort {

    init {
        auditLogger.info(
            "system", AuditAction.FhirSearchAdapterActive,
            "FHIR search adapter active: revinclude (requires server-side _revinclude support)"
        )
    }

    override fun searchHealthcareServices(name: String): HealthcareServiceSearchBundle {
        val terms = searchTermsOf(name)
        if (terms.isEmpty()) return HealthcareServiceSearchBundle(emptyList(), emptyList())

        val byName = searchHealthcareServicesByName(terms)
        val byOrganization = searchHealthcareServicesByOrganization(terms)

        val resources = (byName.entry + byOrganization.entry).map { it.resource }

        return HealthcareServiceSearchBundle(
            healthcareServices = resources.filterIsInstance<HealthcareService>()
                .map(healthcareServiceMapper::toDomain)
                .filter { it.mxid != null },
            organizations = resources.filterIsInstance<Organization>()
                .map(organizationMapper::toDomain)
        )
    }

    private fun searchHealthcareServicesByName(terms: List<String>): Bundle = fhirClient.search<Bundle>()
        .forResource(HealthcareService::class.java)
        .where(HealthcareService.NAME.contains().values(terms))
        .include(Include("HealthcareService:organization"))
        .include(Include("Organization:partof").asRecursive())
        .returnBundle(Bundle::class.java)
        .execute()

    private fun searchHealthcareServicesByOrganization(terms: List<String>): Bundle = fhirClient.search<Bundle>()
        .forResource(Organization::class.java)
        .where(Organization.NAME.contains().values(terms))
        .revInclude(Include("Organization:partof").asRecursive())
        .revInclude(Include("HealthcareService:organization").asRecursive())
        .returnBundle(Bundle::class.java)
        .execute()

    private fun searchByMxid(mxid: Mxid): List<IBaseResource> {
        val matrixUri = TelecomMxidMapper.MXID_INDICATOR + mxid.value.removePrefix("@")

        return fhirClient.search<Bundle>()
            .forResource(HealthcareService::class.java)
            .where(TokenClientParam("telecom").exactly().systemAndCode(TelecomMxidMapper.TELECOM_SYSTEM, matrixUri))
            .include(Include("Organization:partof").asRecursive())
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .map { it.resource }
    }

    /**
     * Searches healthcare services and their organizations whose telecom matches [mxid].
     */
    override fun search(mxid: Mxid): HealthcareServiceSearchBundle {
        val resources = searchByMxid(mxid)

        return HealthcareServiceSearchBundle(
            healthcareServices = resources.filterIsInstance<HealthcareService>()
                .distinctBy { it.idElement.idPart }
                .map { healthcareServiceMapper.toDomain(it) },
            organizations = resources.filterIsInstance<Organization>()
                .distinctBy { it.idElement.idPart }
                .map { organizationMapper.toDomain(it) },
        )
    }
}
