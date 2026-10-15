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

package io.cloudflight.pgk.proxy.search.adapter.outbound

import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.IQuery
import ca.uhn.fhir.rest.gclient.IUntypedQuery
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.HealthcareServiceSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.PractitionerSearchMapper
import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.TelecomMxidMapper
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Resource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapiFhirVisibilityAdapterTest {

    private val properties = ProxyProperties(
        idp = ProxyProperties.IdpProperties(identifierSystem = "https://idp.test/realms/pgk")
    )

    private val practitionerMapper = PractitionerSearchMapper(TelecomMxidMapper(properties))
    private val healthcareServiceMapper = HealthcareServiceSearchMapper(TelecomMxidMapper(properties))

    private fun bundleWith(vararg resources: Resource): Bundle =
        Bundle().apply {
            resources.forEach { addEntry(BundleEntryComponent().apply { resource = it }) }
        }

    private fun practitioner(id: String, mxid: String?): Practitioner = Practitioner().apply {
        this.id = id
        addName().setFamily("Berger").addGiven("Anna")
        mxid?.let { addTelecom(telecomFor(it)) }
    }

    private fun healthcareService(id: String, mxid: String?): HealthcareService = HealthcareService().apply {
        this.id = id
        name = "Radiologie"
        mxid?.let { addTelecom(telecomFor(it)) }
    }

    private fun telecomFor(mxid: String): ContactPoint = ContactPoint().apply {
        system = ContactPoint.ContactPointSystem.URL
        value = MxidTelecom.toTelecomValue(Mxid(mxid))
    }

    private fun clientSearching(type: Class<out IBaseResource>, bundle: Bundle): IGenericClient {
        val query = mockk<IQuery<Bundle>>()
        every { query.count(any()) } returns query
        every { query.returnBundle(Bundle::class.java) } returns query
        every { query.execute() } returns bundle
        val untyped = mockk<IUntypedQuery<Bundle>>()
        every { untyped.forResource(type) } returns query
        val client = mockk<IGenericClient>()
        every { client.search<Bundle>() } returns untyped
        return client
    }

    private fun adapterFor(client: IGenericClient) =
        HapiFhirVisibilityAdapter(client, practitionerMapper, healthcareServiceMapper, AuditLogger())

    // --- Listing ------------------------------------------------------------

    @Test
    fun `practitioner list contains every entry with an mxid`() {
        val client = clientSearching(
            Practitioner::class.java,
            bundleWith(
                practitioner("1", mxid = "@anna:matrix.pgk.example"),
                practitioner("2", mxid = null),
                practitioner("3", mxid = "@bob:matrix.pgk.example")
            )
        )

        val result = adapterFor(client).listProvisionedPractitioners()

        assertEquals(listOf("Practitioner/1", "Practitioner/3"), result.map { it.id })
        assertEquals("@anna:matrix.pgk.example", result.first().mxid?.value)
        assertEquals("Berger, Anna", result.first().name)
    }

    @Test
    fun `healthcare service list contains only entries with an mxid`() {
        val client = clientSearching(
            HealthcareService::class.java,
            bundleWith(
                healthcareService("10", mxid = "@radio:matrix.pgk.example"),
                healthcareService("11", mxid = null)
            )
        )

        val result = adapterFor(client).listProvisionedHealthcareServices()

        assertEquals(listOf("HealthcareService/10"), result.map { it.id })
        assertEquals("Radiologie", result.single().name)
    }

    // --- Writing ------------------------------------------------------------

    @Test
    fun `setVisibility removes all visibility tags and adds the requested one`() {
        val client = mockk<IGenericClient>(relaxed = true)
        val adapter = adapterFor(client)
        val deleted = slot<Meta>()
        val added = slot<Meta>()
        every { client.meta().delete().onResource(any<IIdType>()).meta(capture(deleted)) } returns mockk(relaxed = true)
        every { client.meta().add().onResource(any<IIdType>()).meta(capture(added)) } returns mockk(relaxed = true)
        every { client.read().resource(any<String>()).withId(any<IIdType>()).execute() } returns
                Practitioner().apply { meta = Meta().addSecurity(Visibility.INTERNAL.toCoding()) }

        adapter.setVisibility("Practitioner/1", Visibility.INTERNAL)

        assertTrue(deleted.isCaptured, "old visibility tags must be removed")
        assertTrue(added.isCaptured, "the requested visibility tag must be added")
        assertEquals(
            listOf(Visibility.PUBLIC, Visibility.INTERNAL),
            deleted.captured.security.map { Visibility.fromCode(it.code) }
        )
        assertEquals(listOf(Visibility.INTERNAL), added.captured.security.map { Visibility.fromCode(it.code) })
    }
}
