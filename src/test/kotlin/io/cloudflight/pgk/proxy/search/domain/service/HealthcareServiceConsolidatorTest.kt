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

package io.cloudflight.pgk.proxy.search.domain.service

import io.cloudflight.pgk.proxy.search.domain.model.HealthcareServiceSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawHealthcareService
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HealthcareServiceConsolidatorTest {

    private val consolidator = HealthcareServiceConsolidator(AuditLogger())

    private val mxid = Mxid("@lab:example.com")

    private fun service(
        id: String = "HealthcareService/1",
        name: String = "Radiologie",
        serviceMxid: Mxid? = mxid,
        orgRef: String? = "Organization/1"
    ) = RawHealthcareService(
        id = id,
        name = name,
        mxid = serviceMxid,
        organizationReference = orgRef
    )

    private fun org(id: String = "Organization/1", name: String = "AKH", partOf: String? = null) =
        RawOrganization(id = id, name = name, partOfReference = partOf)

    @Test
    fun `consolidates a service with organization`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(service()),
            organizations = listOf(org())
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(mxid, result?.mxid)
        assertEquals("Radiologie, AKH", result?.displayName)
        assertEquals("AKH", result?.rootOrganization?.name)
    }

    @Test
    fun `walks the organization chain for a service`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(service(orgRef = "Organization/1")),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                org("Organization/9", "AKH")
            )
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals("Radiologie, Kardiologie AKH", result?.displayName)
    }

    @Test
    fun `appends empty organization when service has no organization`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(service(orgRef = null)),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals("Radiologie, ", result?.displayName)
    }

    @Test
    fun `returns null when no service matches the mxid`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = emptyList(),
            organizations = emptyList()
        )

        assertNull(consolidator.consolidate(bundle, mxid))
    }

    @Test
    fun `returns null when service mxid differs from requested mxid`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(service(serviceMxid = Mxid("@other:example.com"))),
            organizations = emptyList()
        )

        assertNull(consolidator.consolidate(bundle, mxid))
    }

    @Test
    fun `uses only the first service when multiple share the same mxid`() {
        val bundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(
                service(id = "HealthcareService/1", name = "Radiologie"),
                service(id = "HealthcareService/2", name = "Nuklearmedizin")
            ),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals("Radiologie, ", result?.displayName)
    }
}
