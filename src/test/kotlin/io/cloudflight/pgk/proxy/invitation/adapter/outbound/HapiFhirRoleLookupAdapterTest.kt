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
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerRoleInfo
import io.cloudflight.pgk.proxy.invitation.mapper.PractitionerRoleMapper
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.mockk.every
import io.mockk.mockk
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.PractitionerRole
import org.hl7.fhir.r4.model.Resource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapiFhirRoleLookupAdapterTest {

    private val properties = ProxyProperties(
        idp = ProxyProperties.IdpProperties(telecomSystem = "url")
    )
    private val client = mockk<IGenericClient>()
    private val adapter = HapiFhirRoleLookupAdapter(client, properties, PractitionerRoleMapper())

    private fun bundleWith(vararg resources: Resource): Bundle =
        Bundle().apply { resources.forEach { addEntry(BundleEntryComponent().apply { resource = it }) } }

    private fun queryReturning(bundle: Bundle): IQuery<Bundle> {
        val query = mockk<IQuery<Bundle>>()
        every { query.where(any<ICriterion<*>>()) } returns query
        every { query.returnBundle(Bundle::class.java) } returns query
        every { query.execute() } returns bundle
        return query
    }

    private fun clientReturning(practitionerBundle: Bundle, roleBundle: Bundle): IGenericClient {
        val untyped = mockk<IUntypedQuery<Bundle>>()
        every { untyped.forResource(Practitioner::class.java) } returns queryReturning(practitionerBundle)
        every { untyped.forResource(PractitionerRole::class.java) } returns queryReturning(roleBundle)
        every { client.search<Bundle>() } returns untyped
        return client
    }

    @Test
    fun `looks up the sender's roles by mxid`() {
        val practitioner = Practitioner().apply { id = "123" }
        val role = PractitionerRole().apply {
            id = "r-1"
            code.add(CodeableConcept().addCoding(Coding("https://roles.example", "cardio", "Kardiologie")))
        }
        clientReturning(bundleWith(practitioner), bundleWith(role))

        val roles = adapter.lookupRoles(Mxid("@bob:matrix.pgk.example"))

        assertEquals(
            listOf(PractitionerRoleInfo("cardio", "https://roles.example", TrustLevel.TL_1, null)),
            roles
        )
    }

    @Test
    fun `returns empty when no practitioner matches the mxid`() {
        clientReturning(bundleWith(), bundleWith())

        assertTrue(adapter.lookupRoles(Mxid("@bob:matrix.pgk.example")).isEmpty())
    }
}
