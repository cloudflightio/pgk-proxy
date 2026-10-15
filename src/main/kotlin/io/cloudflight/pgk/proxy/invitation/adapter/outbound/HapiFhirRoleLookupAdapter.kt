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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerRoleInfo
import io.cloudflight.pgk.proxy.invitation.mapper.PractitionerRoleMapper
import io.cloudflight.pgk.proxy.invitation.port.RoleLookupPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.PractitionerRole
import org.springframework.stereotype.Component

/**
 * Resolves the roles of a federated user (mxid) from the FHIR provider directory, so the
 * invitation evaluator can enforce role-based personal blacklist rules.
 */
@Component
class HapiFhirRoleLookupAdapter(
    private val fhirClient: IGenericClient,
    private val properties: ProxyProperties,
    private val roleMapper: PractitionerRoleMapper
) : RoleLookupPort {

    override fun lookupRoles(mxid: Mxid): List<PractitionerRoleInfo> {
        val practitionerIds = practitionersByMxid(mxid)
        if (practitionerIds.isEmpty()) return emptyList()
        return rolesOf(practitionerIds).map(roleMapper::toDomain)
    }

    private fun practitionersByMxid(mxid: Mxid): List<String> =
        fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(
                Practitioner.TELECOM.exactly()
                    .systemAndCode(properties.idp.telecomSystem, MxidTelecom.toTelecomValue(mxid))
            )
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { it.resource as? Practitioner }
            .map { "Practitioner/${it.idElement.idPart}" }
            .distinct()

    private fun rolesOf(practitionerIds: List<String>): List<PractitionerRole> =
        fhirClient.search<Bundle>()
            .forResource(PractitionerRole::class.java)
            .where(ReferenceClientParam("practitioner").hasAnyOfIds(practitionerIds))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { it.resource as? PractitionerRole }
            .distinctBy { it.idElement.idPart }
}
