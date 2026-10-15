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

import ca.uhn.fhir.rest.api.MethodOutcome
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ICriterion
import ca.uhn.fhir.rest.gclient.IDelete
import ca.uhn.fhir.rest.gclient.IDeleteTyped
import ca.uhn.fhir.rest.gclient.IDeleteWithQueryTyped
import ca.uhn.fhir.rest.gclient.IQuery
import ca.uhn.fhir.rest.gclient.IUntypedQuery
import ca.uhn.fhir.rest.gclient.IUpdate
import ca.uhn.fhir.rest.gclient.IUpdateTyped
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper.OrganizationWriteMapper
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.PractitionerRole
import org.hl7.fhir.r4.model.Reference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HapiFhirOrganizationWriteAdapterTest {

    private val mapper = OrganizationWriteMapper()

    private val institut = OrganizationType("502", "Primärversorgungseinheit")
    private val station = OrganizationType("303", "Sanatorium")

    private fun bundleWith(vararg resources: Organization): Bundle =
        Bundle().apply {
            resources.forEach { addEntry(BundleEntryComponent().apply { resource = it }) }
        }

    private fun fhirOrganization(id: String, name: String, parent: String?): Organization =
        Organization().apply {
            setId(id)
            this.name = name
            parent?.let { partOf = Reference("Organization/$it") }
        }

    private fun clientSearching(bundle: Bundle): IGenericClient {
        val query = mockk<IQuery<Bundle>>()
        every { query.where(any<ICriterion<*>>()) } returns query
        every { query.count(any<Int>()) } returns query
        every { query.returnBundle(Bundle::class.java) } returns query
        every { query.execute() } returns bundle
        val untyped = mockk<IUntypedQuery<Bundle>>()
        every { untyped.forResource(Organization::class.java) } returns query
        val client = mockk<IGenericClient>()
        every { client.search<Bundle>() } returns untyped
        return client
    }

    private fun adapterFor(client: IGenericClient) =
        HapiFhirOrganizationWriteAdapter(client, mapper)

    private fun clientReading(organization: Organization?): IGenericClient {
        val client = mockk<IGenericClient>(relaxed = true)
        if (organization == null) {
            every { client.read().resource(any<String>()).withId(any<String>()).execute() }
                .throws(ResourceNotFoundException("not found"))
        } else {
            every { client.read().resource(any<String>()).withId(any<String>()).execute() }
                .returns(organization)
        }
        val updateTyped = mockk<IUpdateTyped>()
        every { updateTyped.execute() } returns MethodOutcome()
        val update = mockk<IUpdate>()
        every { update.resource(any<IBaseResource>()) } returns updateTyped
        every { client.update() } returns update
        return client
    }

    // --- Read ---------------------------------------------------------------

    @Test
    fun `getOrganizations returns the direct children mapped to the domain model`() {
        val client = clientSearching(
            bundleWith(fhirOrganization("o-1", "Abteilung Anästhesiologie", "o-root"))
        )

        val result = adapterFor(client).getOrganizations("o-root")

        assertEquals(1, result.size)
        assertEquals("o-1", result.single().id)
        assertEquals("Abteilung Anästhesiologie", result.single().name)
        assertEquals("o-root", result.single().parentId)
    }

    @Test
    fun `getOrganizations returns an empty list when there are no children`() {
        val client = clientSearching(bundleWith())

        assertEquals(emptyList<OrganizationData>(), adapterFor(client).getOrganizations("o-root"))
    }

    @Test
    fun `getOrganizations returns every organization mapped to the domain model`() {
        val client = clientSearching(
            bundleWith(
                fhirOrganization("o-root", "Klinik Favoriten", null),
                fhirOrganization("o-1", "Abteilung Anästhesiologie", "o-root")
            )
        )

        val result = adapterFor(client).getOrganizations()

        assertEquals(listOf("o-root", "o-1"), result.map { it.id })
        assertEquals("o-root", result[1].parentId)
    }

    @Test
    fun `getOrganization returns the mapped organization`() {
        val client = clientReading(fhirOrganization("o-1", "Abteilung Anästhesiologie", "o-root"))

        val result = adapterFor(client).getOrganization("o-1")

        assertEquals("o-1", result?.id)
        assertEquals("o-root", result?.parentId)
    }

    @Test
    fun `getOrganization returns null when the organization does not exist`() {
        val client = clientReading(null)

        assertNull(adapterFor(client).getOrganization("o-missing"))
    }

    @Test
    fun `update throws when the organization does not exist`() {
        val client = clientReading(null)
        val adapter = adapterFor(client)

        assertThrows(ResourceNotFoundException::class.java) {
            adapter.update(OrganizationData(id = "o-missing", name = "X"))
        }
    }

    // --- Delete -------------------------------------------------------------

    @Test
    fun `remove deletes the organization`() {
        val deleteWithQuery = mockk<IDeleteWithQueryTyped>()
        every { deleteWithQuery.where(any<ICriterion<*>>()) } returns deleteWithQuery
        every { deleteWithQuery.execute() } returns MethodOutcome()
        val deleteTyped = mockk<IDeleteTyped>()
        every { deleteTyped.execute() } returns MethodOutcome()
        val delete = mockk<IDelete>()
        every { delete.resourceConditionalByType(PractitionerRole::class.java) } returns deleteWithQuery
        every { delete.resourceById("Organization", "o-1") } returns deleteTyped
        val client = mockk<IGenericClient>()
        every { client.delete() } returns delete

        adapterFor(client).remove("o-1")

        verify {
            deleteWithQuery.execute()
            deleteTyped.execute()
        }
    }
}
