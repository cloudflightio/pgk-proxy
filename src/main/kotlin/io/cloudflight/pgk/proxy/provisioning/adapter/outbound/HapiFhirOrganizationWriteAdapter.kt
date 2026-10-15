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
import io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper.OrganizationWriteMapper
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.port.CreateOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationByIdQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationsByParentQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.RemoveOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.UpdateOrganizationPort
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.PractitionerRole
import org.springframework.stereotype.Component

/**
 * FHIR-backed create/read/update/delete for the Organizations of the provider directory.
 */
@Component
class HapiFhirOrganizationWriteAdapter(
    private val fhirClient: IGenericClient,
    private val mapper: OrganizationWriteMapper
) : CreateOrganizationPort, UpdateOrganizationPort, RemoveOrganizationPort,
    OrganizationByIdQueryPort, OrganizationsByParentQueryPort, OrganizationQueryPort {

    override fun create(organization: OrganizationData): String =
        fhirClient.create()
            .resource(mapper.toFhir(organization))
            .execute()
            .id
            .idPart

    override fun update(organization: OrganizationData) {
        val id = requireNotNull(organization.id) { "Organization id is required for an update" }
        val existing = readFhir(id)
            ?: throw ResourceNotFoundException("Organization $id does not exist")
        fhirClient.update()
            .resource(mapper.mergeInto(existing, organization))
            .execute()
    }

    override fun remove(id: String) {
        removePractitionerRoles(id)
        try {
            fhirClient.delete().resourceById("Organization", id).execute()
        } catch (_: ResourceNotFoundException) {
        } catch (_: ResourceGoneException) {
        }
    }

    override fun getOrganization(id: String): OrganizationData? =
        readFhir(id)?.let(mapper::toDomain)

    override fun getOrganizations(parentId: String): List<OrganizationData> =
        fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .where(ReferenceClientParam("partof").hasId("Organization/$parentId"))
            .returnBundle(Bundle::class.java)
            .execute()
            .entry.orEmpty()
            .mapNotNull { it.resource as? Organization }
            .map(mapper::toDomain)

    override fun getOrganizations(): List<OrganizationData> {
        val organizations = mutableListOf<Organization>()
        var bundle = fhirClient.search<Bundle>()
            .forResource(Organization::class.java)
            .count(1000)
            .returnBundle(Bundle::class.java)
            .execute()
        while (true) {
            organizations += bundle.entry.orEmpty().mapNotNull { it.resource as? Organization }
            val nextUrl = bundle.getLink(Bundle.LINK_NEXT)?.url
            if (nextUrl == null) break
            bundle = fhirClient.loadPage().byUrl(nextUrl).andReturnBundle(Bundle::class.java).execute()
        }
        return organizations.map(mapper::toDomain)
    }

    private fun readFhir(id: String): Organization? = runCatching {
        fhirClient.read()
            .resource("Organization")
            .withId(id)
            .execute() as Organization
    }.getOrNull()

    private fun removePractitionerRoles(organizationId: String) {
        try {
            fhirClient.delete()
                .resourceConditionalByType(PractitionerRole::class.java)
                .where(ReferenceClientParam("organization").hasId("Organization/$organizationId"))
                .execute()
        } catch (_: ResourceNotFoundException) {
        } catch (_: ResourceGoneException) {
        }
    }
}
