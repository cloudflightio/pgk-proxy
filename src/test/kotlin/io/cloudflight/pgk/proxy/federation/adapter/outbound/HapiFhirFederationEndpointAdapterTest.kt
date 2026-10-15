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

package io.cloudflight.pgk.proxy.federation.adapter.outbound

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.ICriterion
import ca.uhn.fhir.rest.gclient.ICriterionInternal
import ca.uhn.fhir.rest.gclient.IQuery
import ca.uhn.fhir.rest.gclient.IUntypedQuery
import io.cloudflight.pgk.proxy.federation.mapper.FederationEndpointMapper
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.mockk.every
import io.mockk.mockk
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Endpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class HapiFhirFederationEndpointAdapterTest {

    private val mapper = FederationEndpointMapper()

    private fun endpoint(address: String, trustLevel: TrustLevel): Endpoint =
        Endpoint().apply {
            this.address = address
            meta.addSecurity(trustLevel.toCoding())
        }

    private fun bundleWith(vararg endpoints: Endpoint): Bundle =
        Bundle().apply {
            endpoints.forEach { addEntry(BundleEntryComponent().apply { resource = it }) }
        }

    private fun clientReturning(
        bundle: Bundle,
        capturedParams: MutableList<ICriterionInternal> = mutableListOf()
    ): IGenericClient {
        val query = mockk<IQuery<Bundle>>()
        every { query.where(any<ICriterion<*>>()) } answers {
            capturedParams += arg<ICriterion<*>>(0) as ICriterionInternal
            query
        }
        every { query.returnBundle(Bundle::class.java) } returns query
        every { query.execute() } returns bundle

        val untyped = mockk<IUntypedQuery<Bundle>>()
        every { untyped.forResource(Endpoint::class.java) } returns query

        val client = mockk<IGenericClient>()
        every { client.search<Bundle>() } returns untyped
        return client
    }

    private fun adapterFor(bundle: Bundle): HapiFhirFederationEndpointAdapter =
        HapiFhirFederationEndpointAdapter(clientReturning(bundle), mapper)

    @Test
    @DisplayName("returns only tl-4 endpoints")
    fun testReturnsOnlyTl4Endpoints() {
        val adapter = adapterFor(
            bundleWith(
                endpoint("https://matrix.pgk.example", TrustLevel.TL_4),
                endpoint("https://matrix.untrusted.example", TrustLevel.TL_1)
            )
        )

        val result = adapter.fetchFederationEndpoints()

        assertEquals(listOf(Fqdn("matrix.pgk.example")), result.map { it.fqdn })
    }

    @Test
    @DisplayName("deduplicates endpoints with the same FQDN")
    fun testDeduplicatesEndpointsWithSameFqdn() {
        val adapter = adapterFor(
            bundleWith(
                endpoint("https://matrix.pgk.example", TrustLevel.TL_4),
                endpoint("https://matrix.pgk.example/path", TrustLevel.TL_4)
            )
        )

        val result = adapter.fetchFederationEndpoints()

        assertEquals(listOf(Fqdn("matrix.pgk.example")), result.map { it.fqdn })
    }

    @Test
    @DisplayName("returns an empty list when the FHIR server has no endpoints")
    fun testReturnsEmptyListForNoEndpoints() {
        val adapter = adapterFor(bundleWith())

        assertTrue(adapter.fetchFederationEndpoints().isEmpty())
    }

    @Test
    @DisplayName("returns an empty list when no endpoint reaches tl-4")
    fun testReturnsEmptyListWhenNoEndpointReachesTl4() {
        val adapter = adapterFor(
            bundleWith(
                endpoint("https://matrix.untrusted.example", TrustLevel.TL_1),
                endpoint("https://matrix.also-untrusted.example", TrustLevel.TL_3)
            )
        )

        assertTrue(adapter.fetchFederationEndpoints().isEmpty())
    }

    @Test
    @DisplayName("keeps distinct whitelisted FQDNs across multiple endpoints")
    fun testKeepsDistinctWhitelistedFqdns() {
        val adapter = adapterFor(
            bundleWith(
                endpoint("https://matrix.pgk.example", TrustLevel.TL_4),
                endpoint("https://matrix.partner.example", TrustLevel.TL_4)
            )
        )

        val result = adapter.fetchFederationEndpoints().map { it.fqdn }

        assertTrue(result.contains(Fqdn("matrix.pgk.example")))
        assertTrue(result.contains(Fqdn("matrix.partner.example")))
        assertEquals(2, result.size)
    }

    @Test
    @DisplayName("filters the search request by matrix-homeserver connection type")
    fun testFiltersSearchRequestByConnectionType() {
        val capturedParams = mutableListOf<ICriterionInternal>()
        val adapter = HapiFhirFederationEndpointAdapter(
            clientReturning(bundleWith(), capturedParams),
            mapper
        )

        adapter.fetchFederationEndpoints()

        val criterion = capturedParams.single()
        assertEquals("connection-type", criterion.parameterName)
        assertEquals(
            "https://pgk.elga.gv.at/CodeSystem/endpoint-connection-type|matrix-homeserver",
            criterion.getParameterValue(FhirContext.forR4())
        )
    }
}
