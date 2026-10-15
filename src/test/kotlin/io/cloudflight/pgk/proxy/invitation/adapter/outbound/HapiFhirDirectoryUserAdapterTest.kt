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
import ca.uhn.fhir.rest.gclient.ICriterion
import ca.uhn.fhir.rest.gclient.IQuery
import ca.uhn.fhir.rest.gclient.IUntypedQuery
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.Mxid
import io.mockk.every
import io.mockk.mockk
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Resource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HapiFhirDirectoryUserAdapterTest {

    private val properties = ProxyProperties(
        idp = ProxyProperties.IdpProperties(telecomSystem = "url")
    )

    private fun bundleWith(vararg resources: Resource): Bundle =
        Bundle().apply {
            resources.forEach { addEntry(BundleEntryComponent().apply { resource = it }) }
            total = resources.size
        }

    private fun clientReturning(
        practitionerBundle: Bundle,
        healthcareServiceBundle: Bundle
    ): IGenericClient {
        val practitionerQuery = mockk<IQuery<Bundle>>()
        every { practitionerQuery.where(any<ICriterion<*>>()) } returns practitionerQuery
        every { practitionerQuery.summaryMode(any()) } returns practitionerQuery
        every { practitionerQuery.returnBundle(Bundle::class.java) } returns practitionerQuery
        every { practitionerQuery.execute() } returns practitionerBundle

        val healthcareServiceQuery = mockk<IQuery<Bundle>>()
        every { healthcareServiceQuery.where(any<ICriterion<*>>()) } returns healthcareServiceQuery
        every { healthcareServiceQuery.summaryMode(any()) } returns healthcareServiceQuery
        every { healthcareServiceQuery.returnBundle(Bundle::class.java) } returns healthcareServiceQuery
        every { healthcareServiceQuery.execute() } returns healthcareServiceBundle

        val untyped = mockk<IUntypedQuery<Bundle>>()
        every { untyped.forResource(Practitioner::class.java) } returns practitionerQuery
        every { untyped.forResource(HealthcareService::class.java) } returns healthcareServiceQuery

        val client = mockk<IGenericClient>()
        every { client.search<Bundle>() } returns untyped
        return client
    }

    private fun adapterFor(
        practitionerBundle: Bundle,
        healthcareServiceBundle: Bundle = bundleWith()
    ): HapiFhirDirectoryUserAdapter =
        HapiFhirDirectoryUserAdapter(clientReturning(practitionerBundle, healthcareServiceBundle), properties)

    @Test
    @DisplayName("returns true when a Practitioner references the mxid")
    fun returnsTrueWhenPractitionerReferencesMxid() {
        val adapter = adapterFor(bundleWith(Practitioner()))

        assertTrue(adapter.exists(Mxid("@bob:matrix.pgk.example")))
    }

    @Test
    @DisplayName("returns true when a HealthcareService references the mxid")
    fun returnsTrueWhenHealthcareServiceReferencesMxid() {
        val adapter = adapterFor(bundleWith(), bundleWith(HealthcareService()))

        assertTrue(adapter.exists(Mxid("@bob:matrix.pgk.example")))
    }

    @Test
    @DisplayName("returns false when neither a Practitioner nor a HealthcareService references the mxid")
    fun returnsFalseWhenNoResourceReferencesMxid() {
        val adapter = adapterFor(bundleWith())

        assertFalse(adapter.exists(Mxid("@bob:matrix.pgk.example")))
    }
}
