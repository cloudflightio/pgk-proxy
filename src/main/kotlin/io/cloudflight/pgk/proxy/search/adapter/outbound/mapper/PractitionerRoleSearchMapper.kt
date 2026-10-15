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

package io.cloudflight.pgk.proxy.search.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.search.domain.model.RoleCode
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.springframework.stereotype.Component
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept
import org.hl7.fhir.r4.model.PractitionerRole as FhirPractitionerRole

@Component
class PractitionerRoleSearchMapper {

    fun toDomain(role: FhirPractitionerRole): RawPractitionerRole {

        return RawPractitionerRole(
            id = role.idElement.idPart,
            codeDisplay = role.codeFirstRep.text,
            organizationReference = role.organization?.reference,
            practitionerReference = role.practitioner?.reference,
            trustLevel = TrustLevel.fromMeta(role.meta),
            codes = extractCodes(role)
        )
    }

    private fun extractCodes(role: FhirPractitionerRole): List<RoleCode> {
        if (role.code.isEmpty()) return listOf(RoleCode(system = null, code = null, display = role.idElement.idPart))
        return role.code.map { toRoleCode(it, role) }
    }

    private fun toRoleCode(code: FhirCodeableConcept, role: FhirPractitionerRole): RoleCode {
        val coding = code.coding.firstOrNull()
        return RoleCode(
            system = coding?.system,
            code = coding?.code,
            display = code.text
                ?: coding?.display
                ?: coding?.code
                ?: role.idElement.idPart
        )
    }
}
