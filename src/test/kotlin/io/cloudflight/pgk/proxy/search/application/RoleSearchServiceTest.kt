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

import io.cloudflight.pgk.proxy.search.adapter.outbound.mapper.RoleSuggestionMapper
import io.cloudflight.pgk.proxy.search.api.port.CreatePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.port.RemovePractitionerRolePort
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleRemoval
import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.RoleCode
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.search.port.FindPractitionerRolesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoleSearchServiceTest {

    private val findOrganizationByIdPort = mock<FindOrganizationByIdPort>()
    private val findPractitionerRolesPort = mock<FindPractitionerRolesPort>()
    private val createPractitionerRolePort = mock<CreatePractitionerRolePort>()
    private val removePractitionerRolePort = mock<RemovePractitionerRolePort>()
    private val roleCatalogService = mock<RoleCatalogService>()
    private val practitionerConsolidator = mock<PractitionerConsolidator>()

    private lateinit var service: RoleSearchService

    @BeforeEach
    fun setUp() {
        service = RoleSearchService(
            findOrganizationByIdPort,
            findPractitionerRolesPort,
            createPractitionerRolePort,
            removePractitionerRolePort,
            roleCatalogService,
            practitionerConsolidator,
            RoleSuggestionMapper()
        )
    }

    @Test
    @DisplayName("Maps catalog roles to id, title and display, sorted by display")
    fun searchRolesMapsCatalogEntries() {
        val intern = RoleCatalogEntry("https://example/cs", "INTERN", "Internist", title = "Arztrollen", retired = false)
        val hap = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt", title = "Arztrollen", retired = false)
        whenever(roleCatalogService.search("ar")).thenReturn(listOf(intern, hap))

        val result = service.searchRoles("ar")

        assertEquals(
            listOf(
                mapOf("id" to "https://example/cs|HAP", "title" to "Arztrollen", "codeDisplay" to "Hausarzt"),
                mapOf("id" to "https://example/cs|INTERN", "title" to "Arztrollen", "codeDisplay" to "Internist")
            ),
            result
        )
    }

    @Test
    @DisplayName("Distinguishes codes with the same display coming from different value sets via the title")
    fun searchRolesDistinguishesCollisionsByTitle() {
        val hapFach = RoleCatalogEntry("https://cs/fach", "HAP", "Hausarzt", title = "Fachärzte", retired = false)
        val hapGtel = RoleCatalogEntry("https://cs/gtel", "HAP", "Hausarzt", title = "GTel Vogda Rollen", retired = false)
        whenever(roleCatalogService.search("hausarzt")).thenReturn(listOf(hapFach, hapGtel))

        val result = service.searchRoles("hausarzt")

        assertEquals(
            listOf(
                mapOf("id" to "https://cs/fach|HAP", "title" to "Fachärzte", "codeDisplay" to "Hausarzt"),
                mapOf("id" to "https://cs/gtel|HAP", "title" to "GTel Vogda Rollen", "codeDisplay" to "Hausarzt")
            ),
            result
        )
    }

    @Test
    @DisplayName("Returns an empty list for a blank role query")
    fun searchRolesReturnsEmptyListForBlankQuery() {
        assertTrue(service.searchRoles("").isEmpty())
        verify(roleCatalogService, never()).search(any())
    }

    @Test
    @DisplayName("Maps existing roles including the organization chain and codes")
    fun findExistingRolesMapsOrganizationChainAndCodes() {
        val role = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = "Organization/o1",
            codes = listOf(
                RoleCode("https://example/cs", "HAP", "Hausarzt"),
                RoleCode("https://example/cs", "INTERN", "Internist")
            )
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(role))
        whenever(findOrganizationByIdPort.findById("o1")).thenReturn(RawOrganization("Organization/o1", "Klinik West"))
        whenever(practitionerConsolidator.getOrganizationChain(any(), any(), any()))
            .thenReturn(OrgChain(listOf(RawOrganization("Organization/o1", "Klinik West"))))

        val result = service.findExistingRolesForPractitioner("p1")

        assertEquals(1, result.size)
        val entry = result.single()
        assertEquals("role-1", entry["practitionerRoleId"])
        assertEquals("Klinik West", entry["organizationChain"])
        @Suppress("UNCHECKED_CAST")
        val codes = entry["codes"] as List<Map<String, String>>
        assertEquals(
            listOf(
                mapOf("token" to "https://example/cs|HAP", "display" to "Hausarzt"),
                mapOf("token" to "https://example/cs|INTERN", "display" to "Internist")
            ),
            codes
        )
    }

    @Test
    @DisplayName("Shows only the direct organization of the role for a multi-level chain")
    fun findExistingRolesShowsOnlyDirectOrganization() {
        val role = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = "Organization/o1",
            codes = listOf(RoleCode("https://example/cs", "HAP", "Hausarzt"))
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(role))
        whenever(findOrganizationByIdPort.findById("o1")).thenReturn(RawOrganization("Organization/o1", "Kardiologie"))
        whenever(practitionerConsolidator.getOrganizationChain(any(), any(), any())).thenReturn(
            OrgChain(
                listOf(
                    RawOrganization("Organization/o1", "Kardiologie"),
                    RawOrganization("Organization/o2", "Innere Med II"),
                    RawOrganization("Organization/o3", "AKH Wien")
                )
            )
        )

        val result = service.findExistingRolesForPractitioner("p1")

        assertEquals("role-1", result.single()["practitionerRoleId"])
        assertEquals("Kardiologie", result.single()["organizationChain"])
    }

    @Test
    @DisplayName("Filters existing roles by the root organization")
    fun findExistingRolesFiltersByRootOrganization() {
        val roleAkh = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = "Organization/o1",
            codes = listOf(RoleCode("https://example/cs", "HAP", "Hausarzt"))
        )
        val roleKh = RawPractitionerRole(
            id = "role-2",
            practitionerReference = "Practitioner/p1",
            organizationReference = "Organization/o2",
            codes = listOf(RoleCode("https://example/cs", "INTERN", "Internist"))
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(roleAkh, roleKh))
        whenever(findOrganizationByIdPort.findById("o1"))
            .thenReturn(RawOrganization("Organization/o1", "Kardiologie"))
        whenever(findOrganizationByIdPort.findById("o2"))
            .thenReturn(RawOrganization("Organization/o2", "Innere Med"))
        whenever(practitionerConsolidator.getOrganizationChain(any(), any(), any())).thenAnswer { invocation ->
            val org = invocation.getArgument<RawOrganization>(0)
            OrgChain(listOf(org))
        }

        val result = service.findExistingRolesForPractitioner("p1", "Organization/o1")

        assertEquals(listOf("role-1"), result.map { it["practitionerRoleId"] })
    }

    @Test
    @DisplayName("Handles existing roles that have no organization")
    fun findExistingRolesHandlesRolesWithoutOrganization() {
        val role = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = null,
            codes = listOf(RoleCode("https://example/cs", "HAP", "Hausarzt"))
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(role))

        val result = service.findExistingRolesForPractitioner("p1")

        assertEquals(1, result.size)
        assertEquals("role-1", result.single()["practitionerRoleId"])
        assertEquals("", result.single()["organizationChain"])
        @Suppress("UNCHECKED_CAST")
        val codes = result.single()["codes"] as List<Map<String, String>>
        assertEquals(
            listOf(mapOf("token" to "https://example/cs|HAP", "display" to "Hausarzt")),
            codes
        )
    }

    @Test
    @DisplayName("Returns the first resolvable organization for a practitioner")
    fun findPrimaryOrganizationReturnsFirstResolvable() {
        val role = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = "Organization/o1"
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(role))
        whenever(findOrganizationByIdPort.findById("o1")).thenReturn(RawOrganization("o1", "Klinik West"))

        val result = service.findPrimaryOrganizationForPractitioner("p1")

        assertEquals(mapOf("id" to "o1", "name" to "Klinik West"), result)
    }

    @Test
    @DisplayName("Returns null when no role has an organization")
    fun findPrimaryOrganizationReturnsNullWithoutOrganization() {
        val role = RawPractitionerRole(
            id = "role-1",
            practitionerReference = "Practitioner/p1",
            organizationReference = null
        )
        whenever(findPractitionerRolesPort.findPractitionerRoles("p1")).thenReturn(listOf(role))

        assertNull(service.findPrimaryOrganizationForPractitioner("p1"))
    }

    @Test
    @DisplayName("Creates a practitioner role when role tokens and an organization are provided")
    fun applyRoleChangesCreatesPractitionerRole() {
        val entry = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt")
        whenever(roleCatalogService.findByToken("https://example/cs|HAP")).thenReturn(entry)

        service.applyRoleChanges("p1", "o1", listOf("https://example/cs|HAP"), emptyList())

        verify(createPractitionerRolePort).create("p1", "o1", entry)
        verify(removePractitionerRolePort, never()).remove(any())
    }

    @Test
    @DisplayName("Removes a practitioner role for each staged removal")
    fun applyRoleChangesRemovesPractitionerRoles() {
        val removals = listOf(
            RoleRemoval("role-1", "https://example/cs|HAP"),
            RoleRemoval("role-2", "https://example/cs|INTERN")
        )

        service.applyRoleChanges("p1", "o1", emptyList(), removals)

        verify(removePractitionerRolePort).remove("role-1")
        verify(removePractitionerRolePort).remove("role-2")
        verify(createPractitionerRolePort, never()).create(any(), any(), any())
    }

    @Test
    @DisplayName("Removes old roles and creates new ones in the same call")
    fun applyRoleChangesRemovesAndCreatesInSameCall() {
        val entry = RoleCatalogEntry("https://example/cs", "INTERN", "Internist")
        whenever(roleCatalogService.findByToken("https://example/cs|INTERN")).thenReturn(entry)
        val removals = listOf(RoleRemoval("role-1", "https://example/cs|HAP"))

        service.applyRoleChanges("p1", "o1", listOf("https://example/cs|INTERN"), removals)

        verify(removePractitionerRolePort).remove("role-1")
        verify(createPractitionerRolePort).create("p1", "o1", entry)
    }

    @Test
    @DisplayName("Rejects a blank practitioner id")
    fun applyRoleChangesRejectsBlankPractitionerId() {
        assertThrows(IllegalArgumentException::class.java) {
            service.applyRoleChanges("  ", "o1", listOf("tok"), emptyList())
        }
    }

    @Test
    @DisplayName("Rejects when neither role tokens nor removals are given")
    fun applyRoleChangesRejectsWhenNothingGiven() {
        assertThrows(IllegalArgumentException::class.java) {
            service.applyRoleChanges("p1", "o1", emptyList(), emptyList())
        }
    }

    @Test
    @DisplayName("Rejects a blank organization when role tokens are given")
    fun applyRoleChangesRejectsBlankOrganization() {
        assertThrows(IllegalArgumentException::class.java) {
            service.applyRoleChanges("p1", "  ", listOf("https://example/cs|HAP"), emptyList())
        }
    }

    @Test
    @DisplayName("Rejects an unknown role token")
    fun applyRoleChangesRejectsUnknownRoleToken() {
        whenever(roleCatalogService.findByToken("unknown")).thenReturn(null)

        assertThrows(IllegalArgumentException::class.java) {
            service.applyRoleChanges("p1", "o1", listOf("unknown"), emptyList())
        }
        verify(createPractitionerRolePort, never()).create(any(), any(), any())
    }

    @Test
    @DisplayName("Finds a role by token via the catalog service")
    fun findByTokenDelegatesToCatalogService() {
        val entry = RoleCatalogEntry(system = "https://example/cs", code = "HAP", display = "Hausarzt")
        whenever(roleCatalogService.findByToken("https://example/cs|HAP")).thenReturn(entry)

        assertEquals(entry, service.findByToken("https://example/cs|HAP"))
    }
}
