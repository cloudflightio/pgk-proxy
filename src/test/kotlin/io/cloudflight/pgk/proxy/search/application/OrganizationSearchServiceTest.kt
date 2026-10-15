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

package io.cloudflight.pgk.proxy.search.application

import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.OrganizationSuggestionMapper
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.port.FindOrganizationsByNamePort
import io.mockk.every

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrganizationSearchServiceTest {

    private val findOrganizationsByName = mockk<FindOrganizationsByNamePort>()
    private val organizationSuggestionMapper = mockk<OrganizationSuggestionMapper>()

    private val service = OrganizationSearchService(findOrganizationsByName, organizationSuggestionMapper)

    @Test
    fun `maps organizations through the suggestion mapper sorted by name`() {
        val org1 = RawOrganization("Organization/o1", "Kardiologie", partOfReference = "Organization/akh")
        val org2 = RawOrganization("Organization/o2", "Ambulanz")
        every { findOrganizationsByName.searchByName("org") } returns listOf(org1, org2)
        every { organizationSuggestionMapper.toSuggestion(org1, any()) } returns
            mapOf("id" to "Organization/o1", "name" to "Kardiologie - AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(org2, any()) } returns
            mapOf("id" to "Organization/o2", "name" to "Ambulanz")

        val result = service.searchOrganizations("org")

        assertEquals(
            listOf(
                mapOf("id" to "Organization/o2", "name" to "Ambulanz"),
                mapOf("id" to "Organization/o1", "name" to "Kardiologie - AKH Wien")
            ),
            result
        )
    }

    @Test
    fun `returns an empty list for a blank organization query`() {
        assertTrue(service.searchOrganizations("").isEmpty())
        verify(exactly = 0) { findOrganizationsByName.searchByName(any()) }
    }

    @Test
    fun `excludes parent organizations that do not match the query`() {
        val kardiologie = RawOrganization("Organization/kardiologie", "Kardiologie", partOfReference = "Organization/innere")
        val innere = RawOrganization("Organization/innere", "Innere Med II", partOfReference = "Organization/akh")
        val akh = RawOrganization("Organization/akh", "AKH Wien")
        every { findOrganizationsByName.searchByName("kardiologie") } returns
            listOf(kardiologie, innere, akh)
        every { organizationSuggestionMapper.toSuggestion(kardiologie, any()) } returns
            mapOf("id" to "Organization/kardiologie", "name" to "Kardiologie - Innere Med II - AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(innere, any()) } returns
            mapOf("id" to "Organization/innere", "name" to "Innere Med II - AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(akh, any()) } returns
            mapOf("id" to "Organization/akh", "name" to "AKH Wien")

        val result = service.searchOrganizations("kardiologie")

        assertEquals(
            listOf(mapOf("id" to "Organization/kardiologie", "name" to "Kardiologie - Innere Med II - AKH Wien")),
            result
        )
        verify(exactly = 1) { organizationSuggestionMapper.toSuggestion(kardiologie, any()) }
        verify(exactly = 0) { organizationSuggestionMapper.toSuggestion(innere, any()) }
        verify(exactly = 0) { organizationSuggestionMapper.toSuggestion(akh, any()) }
    }

    @Test
    fun `keeps matching and child organizations when searching for a parent`() {
        val akh = RawOrganization("Organization/akh", "AKH Wien")
        val innere = RawOrganization("Organization/innere", "Innere Med II", partOfReference = "Organization/akh")
        val kardiologie = RawOrganization("Organization/kardiologie", "Kardiologie", partOfReference = "Organization/innere")
        every { findOrganizationsByName.searchByName("akh") } returns
            listOf(akh, innere, kardiologie)
        every { organizationSuggestionMapper.toSuggestion(akh, any()) } returns
            mapOf("id" to "Organization/akh", "name" to "AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(innere, any()) } returns
            mapOf("id" to "Organization/innere", "name" to "Innere Med II - AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(kardiologie, any()) } returns
            mapOf("id" to "Organization/kardiologie", "name" to "Kardiologie - Innere Med II - AKH Wien")

        val result = service.searchOrganizations("akh")

        assertEquals(3, result.size)
        verify(exactly = 1) { organizationSuggestionMapper.toSuggestion(akh, any()) }
        verify(exactly = 1) { organizationSuggestionMapper.toSuggestion(innere, any()) }
        verify(exactly = 1) { organizationSuggestionMapper.toSuggestion(kardiologie, any()) }
    }

    @Test
    fun `keeps an organization that matches the query even if it is an ancestor of another match`() {
        val akh = RawOrganization("Organization/akh", "AKH Wien")
        val er = RawOrganization("Organization/er", "AKH Notaufnahme", partOfReference = "Organization/akh")
        every { findOrganizationsByName.searchByName("akh") } returns listOf(akh, er)
        every { organizationSuggestionMapper.toSuggestion(akh, any()) } returns
            mapOf("id" to "Organization/akh", "name" to "AKH Wien")
        every { organizationSuggestionMapper.toSuggestion(er, any()) } returns
            mapOf("id" to "Organization/er", "name" to "AKH Notaufnahme - AKH Wien")

        val result = service.searchOrganizations("akh")

        assertEquals(
            listOf(
                mapOf("id" to "Organization/er", "name" to "AKH Notaufnahme - AKH Wien"),
                mapOf("id" to "Organization/akh", "name" to "AKH Wien")
            ),
            result
        )
    }
}
