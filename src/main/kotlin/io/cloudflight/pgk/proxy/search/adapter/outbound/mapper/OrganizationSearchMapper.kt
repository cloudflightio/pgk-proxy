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

import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import org.hl7.fhir.r4.model.Organization as FhirOrganization
import org.springframework.stereotype.Component

@Component
class OrganizationSearchMapper {

    fun toDomain(organization: FhirOrganization): RawOrganization {

        return RawOrganization(
            id = "Organization/${organization.idPart}",
            name = organization.name,
            partOfReference = organization.partOf.reference,
            alias = organization.alias.lastOrNull()?.value ?: organization.name
        )
    }
}
