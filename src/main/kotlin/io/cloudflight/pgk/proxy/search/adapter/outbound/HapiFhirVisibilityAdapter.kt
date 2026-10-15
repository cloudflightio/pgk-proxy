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

import ca.uhn.fhir.rest.client.api.IGenericClient
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.HealthcareServiceSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerSearchMapper
import io.cloudflight.pgk.proxy.search.api.domain.model.VisibilityEntry
import io.cloudflight.pgk.proxy.search.api.port.HealthcareServiceVisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityUpdatePort
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawHealthcareService
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

/**
 * Read/write access to the FHIR provider directory for the visibility admin page (Sichtbarkeit).
 */
@Component
class HapiFhirVisibilityAdapter(
    private val fhirClient: IGenericClient,
    private val practitionerMapper: PractitionerSearchMapper,
    private val healthcareServiceMapper: HealthcareServiceSearchMapper,
    private val auditLogger: AuditLogger
) : VisibilityQueryPort, HealthcareServiceVisibilityQueryPort, VisibilityUpdatePort {

    override fun listProvisionedPractitioners(): List<VisibilityEntry> =
        fetchAll(Practitioner::class.java)
            .map(practitionerMapper::toDomain)
            .filter { it.mxid != null }
            .map { it.toVisibilityEntry() }

    override fun listProvisionedHealthcareServices(): List<VisibilityEntry> =
        fetchAll(HealthcareService::class.java)
            .map(healthcareServiceMapper::toDomain)
            .filter { it.mxid != null }
            .map { it.toVisibilityEntry() }

    override fun setVisibility(id: String, visibility: Visibility) {
        val fhirId = IdType(id)
        // Replace any previously stored visibility tag so at most one visibility coding remains.
        fhirClient.meta().delete()
            .onResource(fhirId)
            .meta(metaWith(Visibility.PUBLIC, Visibility.INTERNAL))
            .execute()
        fhirClient.meta().add()
            .onResource(fhirId)
            .meta(metaWith(visibility))
            .execute()
        val persisted = readBackVisibility(id, fhirId)
        if (persisted == visibility) {
            auditLogger.info("system", AuditAction.VisibilitySet, "Set visibility of '$id' to ${visibility.code}")
        } else {
            auditLogger.warn(
                "system", AuditAction.VisibilitySetReadBackMismatch,
                "Set visibility of '$id' to ${visibility.code}, but read-back returned $persisted"
            )
        }
    }

    private fun readBackVisibility(id: String, fhirId: IdType): Visibility? = runCatching {
        val resource = fhirClient.read().resource(id.substringBefore("/")).withId(fhirId).execute()
        Visibility.fromMeta(resource.meta as Meta)
    }.getOrNull()

    private fun <T : IBaseResource> fetchAll(type: Class<T>): List<T> {
        val resources = mutableListOf<T>()
        var bundle = fhirClient.search<Bundle>()
            .forResource(type)
            .count(PAGE_SIZE)
            .returnBundle(Bundle::class.java)
            .execute()
        while (true) {
            bundle.entry.orEmpty().mapNotNullTo(resources) { entry ->
                entry.resource?.takeIf(type::isInstance)?.let(type::cast)
            }
            val hasNext = bundle.link.any { it.relation == Bundle.LINK_NEXT }
            if (!hasNext) break
            bundle = fhirClient.loadPage().next<Bundle>(bundle).execute()
        }
        return resources.distinctBy { it.idElement.idPart }
    }

    private fun RawPractitioner.toVisibilityEntry() =
        VisibilityEntry(id = id, name = name, mxid = mxid, visibility = visibility)

    private fun RawHealthcareService.toVisibilityEntry() =
        VisibilityEntry(id = id, name = name, mxid = mxid, visibility = visibility)

    private fun metaWith(vararg visibilities: Visibility): Meta =
        Meta().apply {
            visibilities.forEach { addSecurity(it.toCoding()) }
        }

    companion object {
        const val PAGE_SIZE = 1000
    }
}
