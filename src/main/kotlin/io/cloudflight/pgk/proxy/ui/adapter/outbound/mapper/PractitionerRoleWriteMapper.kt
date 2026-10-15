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

package io.cloudflight.pgk.proxy.ui.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.PractitionerRole
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component

private const val PRACTITIONER_ROLE_PROFILE =
    "http://hl7.at/fhir/HL7ATCoreProfiles/4.0.1/StructureDefinition/at-core-practitionerRole"

@Component
class PractitionerRoleWriteMapper {

    fun toFhir(
        practitionerId: String,
        organizationId: String,
        role: RoleCatalogEntry
    ): PractitionerRole = PractitionerRole().apply {
        practitioner = Reference(fhirResourceId("Practitioner", practitionerId))
        organization = Reference(fhirResourceId("Organization", organizationId))
        addCode(buildCodeableConcept(role))
        stampMeta(this)
    }

    private fun fhirResourceId(type: String, id: String): String =
        if (id.startsWith("$type/")) id else "$type/$id"

    private fun buildCodeableConcept(entry: RoleCatalogEntry): CodeableConcept =
        CodeableConcept().apply {
            addCoding(Coding(entry.system, entry.code, entry.display))
            text = entry.display
        }

    private fun stampMeta(role: PractitionerRole) {
        role.meta = Meta().apply {
            addProfile(PRACTITIONER_ROLE_PROFILE)
            addSecurity(TrustLevel.DEFAULT.toCoding())
        }
        role.active = true
    }
}
