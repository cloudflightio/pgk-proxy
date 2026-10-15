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

package io.cloudflight.pgk.proxy.search.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OrganizationSuggestionMapperTest {

    private val findOrganizationById = mockk<FindOrganizationByIdPort>()
    private val mapper = OrganizationSuggestionMapper(
        practitionerConsolidator = PractitionerConsolidator(findOrganizationById, AuditLogger()),
        findOrganizationById = findOrganizationById
    )

    private fun org(id: String, name: String, partOf: String? = null) =
        RawOrganization(id = id, name = name, partOfReference = partOf)

    @Test
    fun `suggests a single organization by its own name`() {
        val organization = org("Organization/o1", "Ambulanz")

        val result = mapper.toSuggestion(organization, mapOf("Organization/o1" to organization))

        assertEquals(mapOf("id" to "Organization/o1", "name" to "Ambulanz"), result)
    }

    @Test
    fun `joins the organization chain with a dash`() {
        val organization = org("Organization/o1", "Kardiologie", partOf = "Organization/innere")
        every { findOrganizationById.findById("innere") } returns
            org("Organization/innere", "Innere Med II", partOf = "Organization/akh")
        every { findOrganizationById.findById("akh") } returns
            org("Organization/akh", "AKH Wien")

        val result = mapper.toSuggestion(organization, mapOf("Organization/o1" to organization))

        assertEquals(
            mapOf("id" to "Organization/o1", "name" to "Kardiologie - Innere Med II - AKH Wien"),
            result
        )
    }

    @Test
    fun `resolves parents already present in the index`() {
        val organization = org("Organization/o1", "Kardiologie", partOf = "Organization/akh")
        val akh = org("Organization/akh", "AKH Wien")
        val index = mapOf("Organization/o1" to organization, "Organization/akh" to akh)

        val result = mapper.toSuggestion(organization, index)

        assertEquals(
            mapOf("id" to "Organization/o1", "name" to "Kardiologie - AKH Wien"),
            result
        )
    }
}
