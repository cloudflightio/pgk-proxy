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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component

/**
 * Maps the provisioning module's [OrganizationData] to/from FHIR `Organization` resources.
 */
@Component
class OrganizationWriteMapper {

    fun toFhir(organization: OrganizationData): Organization =
        Organization().apply {
            organization.id?.let { setId(it) }
            active = true
            name = organization.name
            partOf = organization.parentId?.takeIf { it.isNotBlank() }?.let { Reference("Organization/$it") }
            organization.type?.let { addType(it.toCodeableConcept()) }
        }

    fun mergeInto(existing: Organization, organization: OrganizationData): Organization =
        existing.apply {
            active = true
            name = organization.name
            partOf = organization.parentId?.takeIf { it.isNotBlank() }?.let { Reference("Organization/$it") }
            type = organization.type?.let { listOf(it.toCodeableConcept()) } ?: emptyList()
        }

    fun toDomain(organization: Organization): OrganizationData {
        val type = organization.type.firstNotNullOfOrNull { it.coding.firstOrNull() }
            ?.let { OrganizationType(code = it.code, display = it.display ?: it.code) }
        return OrganizationData(
            id = organization.idElement.idPart,
            name = organization.name,
            parentId = organization.partOf?.reference
                ?.removePrefix("Organization/")
                ?.takeIf { it.isNotBlank() },
            type = type
        )
    }

    private fun OrganizationType.toCodeableConcept(): CodeableConcept =
        CodeableConcept().apply {
            addCoding(Coding().apply {
                system = OrganizationType.SYSTEM
                code = this@toCodeableConcept.code
                display = this@toCodeableConcept.display
            })
        }
}
