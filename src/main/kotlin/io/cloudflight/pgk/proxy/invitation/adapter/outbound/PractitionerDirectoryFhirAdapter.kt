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
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.infrastructure.fhir.HapiBundlePager
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerDirectoryEntry
import io.cloudflight.pgk.proxy.invitation.mapper.PractitionerDirectoryMapper
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

/**
 * Read-only access to the FHIR provider directory for the invitation-rights admin page.
 *
 * Returns identity (id, name, mxid) for every practitioner that carries a Matrix mxid in
 * its telecom. Rights live in the persistence layer.
 */
@Component
class PractitionerDirectoryFhirAdapter(
    private val fhirClient: IGenericClient,
    private val mapper: PractitionerDirectoryMapper,
    private val properties: ProxyProperties
) : PractitionerDirectoryPort, FindPractitionerByMxidPort {

    override fun list(): List<PractitionerDirectoryEntry> {
        val practitioners = HapiBundlePager.allResources(
            fhirClient,
            fhirClient.search<Bundle>()
                .forResource(Practitioner::class.java)
                .count(HapiBundlePager.PAGE_SIZE)
                .returnBundle(Bundle::class.java)
                .execute()
        ).filterIsInstance<Practitioner>()
            .distinctBy { it.idElement.idPart }
            .map(mapper::toEntry)
            .filter { it.mxid != null }

        return practitioners
    }

    override fun findByMxid(mxid: Mxid): PractitionerDirectoryEntry? {
        val practitioners = fhirClient.search<Bundle>()
            .forResource(Practitioner::class.java)
            .where(Practitioner.TELECOM.exactly().systemAndCode(properties.idp.telecomSystem, MxidTelecom.toTelecomValue(mxid)))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { it.resource as? Practitioner }
            .distinctBy { it.idElement.idPart }
        val idpManaged = practitioners.filter { practitioner ->
            practitioner.identifier.any { it.system == properties.idp.identifierSystem }
        }
        return (idpManaged.ifEmpty { practitioners })
            .map(mapper::toEntry)
            .firstOrNull { it.mxid == mxid }
    }
}
