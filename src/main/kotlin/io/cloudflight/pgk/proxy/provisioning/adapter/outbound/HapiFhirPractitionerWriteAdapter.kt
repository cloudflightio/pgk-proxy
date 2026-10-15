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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper.PractitionerWriteMapper
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.provisioning.port.FindUserIdsByMxidPort
import io.cloudflight.pgk.proxy.provisioning.port.RemovePractitionerPort
import io.cloudflight.pgk.proxy.provisioning.port.WritePractitionerPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.PractitionerRole
import org.springframework.stereotype.Component

@Component
class HapiFhirPractitionerWriteAdapter(
    private val fhirClient: IGenericClient,
    private val practitionerMapper: PractitionerWriteMapper,
    private val properties: ProxyProperties
) : WritePractitionerPort, RemovePractitionerPort, FindUserIdsByMxidPort {

    override fun write(user: IdpUser) {
        val practitioner = practitionerMapper.toFhir(user)
        fhirClient.update()
            .resource(practitioner)
            .conditional()
            .where(Practitioner.IDENTIFIER.exactly().systemAndCode(properties.idp.identifierSystem, user.userId))
            .execute()
    }

    override fun remove(userId: String) {
        val fhirId = findFhirIdByUserId(userId) ?: return
        removePractitionerRoles(fhirId)
        try {
            fhirClient.delete().resourceById("Practitioner", fhirId).execute()
        } catch (_: ResourceNotFoundException) {
        } catch (_: ResourceGoneException) {
        }
    }

    private fun removePractitionerRoles(practitionerId: String) {
        try {
            fhirClient.delete()
                .resourceConditionalByType(PractitionerRole::class.java)
                .where(ReferenceClientParam("practitioner").hasId(fhirResourceId(practitionerId)))
                .execute()
        } catch (_: ResourceNotFoundException) {
        } catch (_: ResourceGoneException) {
        }
    }

    private fun fhirResourceId(id: String): String = if (id.startsWith("Practitioner/")) id else "Practitioner/$id"

    override fun findUserIdsByMxid(mxid: Mxid): List<String> =
        fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.TELECOM.exactly().systemAndCode(properties.idp.telecomSystem, MxidTelecom.toTelecomValue(mxid)))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { (it.resource as? Practitioner)?.identifier?.firstOrNull { identifier ->
                identifier.system == properties.idp.identifierSystem
            }?.value }
            .distinct()

    private fun findFhirIdByUserId(userId: String): String? =
        fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.IDENTIFIER.exactly().systemAndCode(properties.idp.identifierSystem, userId))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { (it.resource as? Practitioner)?.idElement?.idPart }
            .firstOrNull()
}
