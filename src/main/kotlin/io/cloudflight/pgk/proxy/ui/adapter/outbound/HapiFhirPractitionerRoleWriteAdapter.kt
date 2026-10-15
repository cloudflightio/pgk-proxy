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

package io.cloudflight.pgk.proxy.ui.adapter.outbound

import ca.uhn.fhir.rest.client.api.IGenericClient
import io.cloudflight.pgk.proxy.search.api.port.CreatePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.port.RemovePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.ui.adapter.outbound.mapper.PractitionerRoleWriteMapper
import org.springframework.stereotype.Component

@Component
class HapiFhirPractitionerRoleWriteAdapter(
    private val fhirClient: IGenericClient,
    private val practitionerRoleMapper: PractitionerRoleWriteMapper
) : CreatePractitionerRolePort, RemovePractitionerRolePort {

    override fun create(
        practitionerId: String,
        organizationId: String,
        role: RoleCatalogEntry
    ): String {
        val practitionerRole = practitionerRoleMapper.toFhir(practitionerId, organizationId, role)
        return fhirClient
            .create()
            .resource(practitionerRole)
            .execute()
            .id.idPart
    }

    override fun remove(practitionerRoleId: String) {
        fhirClient.delete().resourceById("PractitionerRole", practitionerRoleId).execute()
    }
}
