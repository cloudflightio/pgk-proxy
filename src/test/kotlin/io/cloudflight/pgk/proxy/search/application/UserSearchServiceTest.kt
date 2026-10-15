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

import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRoleQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUserQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsByMxidQueryPort
import io.cloudflight.pgk.proxy.search.domain.model.*
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawHealthcareService
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.domain.service.HealthcareServiceConsolidator
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.*
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserSearchServiceTest {

    private val practitionersPort = mockk<SearchPractitionersByNamePort>()
    private val practitionersMxidPort = mockk<SearchPractitionersByMxidPort>()
    private val practitionersIdentifierPort = mockk<SearchPractitionersByIdentifierPort>()
    private val healthcareServicesPort = mockk<SearchHealthcareServicesPort>()
    private val healthcareServicesMxidPort = mockk<SearchHealthcareServicesByMxidPort>()
    private val practitionerConsolidator = mockk<PractitionerConsolidator>()
    private val healthcareServiceConsolidator = mockk<HealthcareServiceConsolidator>()
    private val findOrganizationByIdPort = mockk<FindOrganizationByIdPort>()
    private val blockedUserPort = mockk<OrgBlockedUserQueryPort>()
    private val blockedRolePort = mockk<OrgBlockedRoleQueryPort>()
    private val userInvitationRightsPort = mockk<UserInvitationRightsByMxidQueryPort>()
    private val properties = ProxyProperties()

    private val service = UserSearchService(
        practitionersPort = practitionersPort,
        practitionersMxidPort = practitionersMxidPort,
        practitionersIdentifierPort = practitionersIdentifierPort,
        healthcareServicesMxidPort = healthcareServicesMxidPort,
        healthcareServicesPort = healthcareServicesPort,
        practitionerConsolidator = practitionerConsolidator,
        healthcareServiceConsolidator = healthcareServiceConsolidator,
        findOrganizationById = findOrganizationByIdPort,
        properties = properties,
        blockedUserPort = blockedUserPort,
        blockedRolePort = blockedRolePort,
        userInvitationRightsPort = userInvitationRightsPort,
        auditLogger = AuditLogger()
    )

    @BeforeEach
    fun init() {
        every { blockedUserPort.isBlocked(any()) } returns false
        every { blockedRolePort.isAnyRoleBlocked(any(), any()) } returns false
        every { userInvitationRightsPort.getPractitionerRights(any()) } returns null

        every { practitionersMxidPort.search(userMxid) } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner(userMxid)),
            practitionerRoles = listOf(),
            organizations = listOf(),
        )

        every { healthcareServicesMxidPort.search(userMxid) } returns HealthcareServiceSearchBundle(
            healthcareServices = listOf(healthcareService(userMxid)),
            organizations = listOf(),
        )

        every { practitionerConsolidator.consolidate(any(), any<Mxid>()) } answers {
            ConsolidatedUser(
                mxid = secondArg(),
                displayName = secondArg<Mxid>().value,
                roles = listOf(),
                organizationChains = listOf(),
                visibility = Visibility.DEFAULT
            )
        }
    }

    private val mxidA = Mxid("@alice:example.com")
    private val mxidB = Mxid("@bob:example.com")
    private val userMxid = Mxid("@max:example.com")

    private fun practitioner(mxid: Mxid?) = RawPractitioner(
        id = "Practitioner/${mxid?.value.hashCode()}",
        name = "Some Name",
        trustLevel = TrustLevel.TL_1,
        mxid = mxid
    )

    private fun healthcareService(mxid: Mxid?) = RawHealthcareService(
        id = "HealthcareService/${mxid?.value.hashCode()}",
        name = "Some Service",
        mxid = mxid,
        organizationReference = null
    )

    @Test
    fun `combines practitioner and healthcare service results`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val healthcareBundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(healthcareService(mxidB)),
            organizations = emptyList()
        )

        every { practitionersPort.search("alice") } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices("alice") } returns healthcareBundle
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns
                ConsolidatedUser(
                    mxid = mxidA,
                    displayName = "Alice",
                    roles = listOf(),
                    organizationChains = listOf(),
                    visibility = Visibility.DEFAULT
                )
        every { healthcareServiceConsolidator.consolidate(healthcareBundle, mxidB) } returns
                ConsolidatedUser(
                    mxid = mxidB,
                    displayName = "Alice Service",
                    roles = listOf(),
                    organizationChains = listOf(),
                    visibility = Visibility.DEFAULT
                )

        val result = service.search("alice", userMxid)

        assertEquals(2, result.results.size)
        assertEquals(setOf(mxidA.value, mxidB.value), result.results.map { it.userId }.toSet())
    }

    @Test
    fun `dedupes practitioners sharing the same mxid`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA), practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        every { practitionersPort.search(any()) } returns bundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(bundle, mxidA) } returns ConsolidatedUser(
            mxid = mxidA,
            displayName = "x",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.DEFAULT
        )

        val result = service.search("x", userMxid)

        assertEquals(listOf(mxidA.value), result.results.map { it.userId })
        verify(exactly = 1) { practitionerConsolidator.consolidate(bundle, mxidA) }
    }

    @Test
    fun `excludes practitioners without an mxid`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(null)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        every { practitionersPort.search(any()) } returns bundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        val result = service.search("x", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `marks results as not limited`() {
        every { practitionersPort.search(any()) } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        val result = service.search("x", userMxid)

        assertFalse(result.limited)
    }

    @Test
    fun `returns empty results for empty inputs`() {
        every { practitionersPort.search(any()) } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        val result = service.search("x", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `forwards the search term to both ports`() {
        every { practitionersPort.search("alice") } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesPort.searchHealthcareServices("alice") } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        service.search("alice", userMxid)

        verify { practitionersPort.search("alice") }
        verify { healthcareServicesPort.searchHealthcareServices("alice") }
    }

    @Test
    fun `drops consolidated entries that return null`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns null

        val result = service.search("x", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `maps practitioners id name and trust level sorted by name`() {
        val practitionerA =
            RawPractitioner(id = "Practitioner/a", name = "Zoe Alice", mxid = mxidA, trustLevel = TrustLevel.TL_2)
        val practitionerB =
            RawPractitioner(id = "Practitioner/b", name = "Anna Alice", mxid = mxidB, trustLevel = TrustLevel.TL_1)
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitionerA, practitionerB),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        every { practitionersPort.search("alice") } returns bundle
        every { practitionerConsolidator.buildDisplayName(any(), any()) } answers {
            val practitioner = firstArg<RawPractitioner>()
            if (practitioner.id == "Practitioner/a") "Dr. Zoe" else "Anna"
        }

        val result = service.searchPractitioners("alice")

        assertEquals(
            listOf(
                mapOf(
                    "id" to "Practitioner/b", "name" to "Anna", "trustLevel" to "TL_1",
                    "mxid" to mxidB.value, "organizationId" to "", "organizationName" to ""
                ),
                mapOf(
                    "id" to "Practitioner/a", "name" to "Dr. Zoe", "trustLevel" to "TL_2",
                    "mxid" to mxidA.value, "organizationId" to "", "organizationName" to ""
                )
            ),
            result
        )
    }

    @Test
    fun `returns an empty list for a blank practitioner query`() {
        assertTrue(service.searchPractitioners("  ").isEmpty())
        verify(exactly = 0) { practitionersPort.search(any()) }
    }

    @Test
    fun `returns the exact mxid match when the query is a valid mxid`() {
        val byNameBundle = PractitionerSearchBundle(
            practitioners = listOf(
                RawPractitioner(id = "Practitioner/other", name = "Other", mxid = mxidB, trustLevel = TrustLevel.TL_1)
            ),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        every { practitionersPort.search("@alice:example.com") } returns byNameBundle
        every { practitionersMxidPort.search(mxidA) } returns PractitionerSearchBundle(
            practitioners = listOf(
                RawPractitioner(id = "Practitioner/a", name = "Alice", mxid = mxidA, trustLevel = TrustLevel.TL_2)
            ),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        every { practitionerConsolidator.buildDisplayName(any(), any()) } answers {
            firstArg<RawPractitioner>().name
        }

        val result = service.searchPractitioners("@alice:example.com")

        assertEquals(
            listOf(
                mapOf(
                    "id" to "Practitioner/a", "name" to "Alice", "trustLevel" to "TL_2",
                    "mxid" to mxidA.value, "organizationId" to "", "organizationName" to ""
                )
            ),
            result
        )
        verify { practitionersMxidPort.search(mxidA) }
    }

    @Test
    fun `does not search by mxid for a plain name query`() {
        every { practitionersPort.search("alice") } returns
            PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { practitionerConsolidator.buildDisplayName(any(), any()) } returns "Alice"

        service.searchPractitioners("alice")

        verify(exactly = 0) { practitionersMxidPort.search(any()) }
    }

    @Test
    fun `resolves the practitioner of an IDP user by identifier`() {
        val practitioner = RawPractitioner(
            id = "Practitioner/p1",
            name = "Dr. Mustermann, Max",
            trustLevel = TrustLevel.TL_2,
            mxid = userMxid
        )
        every { practitionersIdentifierPort.searchByIdentifier(properties.idp.identifierSystem, "user-1") } returns
            PractitionerSearchBundle(listOf(practitioner), emptyList(), emptyList())
        every { practitionerConsolidator.buildDisplayName(any(), any()) } returns "Dr. Mustermann, Max"

        val result = service.findPractitionerByUserId("user-1")

        assertEquals(
            mapOf(
                "id" to "Practitioner/p1", "name" to "Dr. Mustermann, Max", "trustLevel" to "TL_2",
                "mxid" to userMxid.value, "organizationId" to "", "organizationName" to ""
            ),
            result
        )
        verify { practitionersIdentifierPort.searchByIdentifier(properties.idp.identifierSystem, "user-1") }
    }

    @Test
    fun `returns null when the IDP user has no practitioner`() {
        every { practitionersIdentifierPort.searchByIdentifier(any(), any()) } returns
            PractitionerSearchBundle(emptyList(), emptyList(), emptyList())

        assertNull(service.findPractitionerByUserId("unknown-user"))
    }

    @Test
    fun `resolves the current user's practitioner even without an mxid`() {
        val practitioner = RawPractitioner(
            id = "Practitioner/p1",
            name = "Dr. Mustermann, Max",
            trustLevel = TrustLevel.TL_2,
            mxid = null
        )
        every { practitionersIdentifierPort.searchByIdentifier(any(), "user-1") } returns
            PractitionerSearchBundle(listOf(practitioner), emptyList(), emptyList())
        every { practitionerConsolidator.buildDisplayName(any(), any()) } returns "Dr. Mustermann, Max"

        assertEquals(
            mapOf(
                "id" to "Practitioner/p1", "name" to "Dr. Mustermann, Max", "trustLevel" to "TL_2",
                "mxid" to "", "organizationId" to "", "organizationName" to ""
            ),
            service.findPractitionerByUserId("user-1")
        )
    }

    @Test
    fun `returns null for a blank IDP user id`() {
        assertNull(service.findPractitionerByUserId("  "))
        verify(exactly = 0) { practitionersIdentifierPort.searchByIdentifier(any(), any()) }
    }

    @Test
    fun `suggests a practitioner separately per root organization`() {
        val practitioner = RawPractitioner(
            id = "Practitioner/p1",
            name = "Dr. Mustermann, Max",
                trustLevel = TrustLevel.TL_2,
            mxid = mxidA
        )
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner),
            practitionerRoles = listOf(
                RawPractitionerRole(
                    codeDisplay = "Oberarzt", practitionerReference = "Practitioner/p1",
                    trustLevel = TrustLevel.TL_1, organizationReference = "Organization/akh"
                ),
                RawPractitionerRole(
                    codeDisplay = "Leitung", practitionerReference = "Practitioner/p1",
                    trustLevel = TrustLevel.TL_1, organizationReference = "Organization/kh"
                )
            ),
            organizations = listOf(
                RawOrganization(id = "Organization/akh", name = "AKH Wien"),
                RawOrganization(id = "Organization/kh", name = "KH Nord")
            )
        )
        every { practitionersPort.search("mustermann") } returns bundle
        every { practitionerConsolidator.getOrganizationChain(any(), any(), any()) } answers {
            val org = firstArg<RawOrganization>()
            OrgChain(listOf(org))
        }
        every { practitionerConsolidator.buildDisplayName(any(), any()) } answers {
            val roleZip = secondArg<List<Pair<RawPractitionerRole, OrgChain>>>()
            "Dr. Mustermann, Max (${roleZip.map { it.first.codeDisplay }.joinToString()})"
        }

        val result = service.searchPractitioners("mustermann")

        assertEquals(
            listOf(
                mapOf(
                    "id" to "Practitioner/p1", "name" to "Dr. Mustermann, Max (Leitung)",
                    "trustLevel" to "TL_2", "mxid" to mxidA.value, "organizationId" to "Organization/kh",
                    "organizationName" to "KH Nord"
                ),
                mapOf(
                    "id" to "Practitioner/p1", "name" to "Dr. Mustermann, Max (Oberarzt)",
                    "trustLevel" to "TL_2", "mxid" to mxidA.value, "organizationId" to "Organization/akh",
                    "organizationName" to "AKH Wien"
                )
            ),
            result
        )
    }

    @Test
    fun `keeps only practitioners matching every term`() {
        val maxAkh = RawPractitioner(
            id = "Practitioner/p1", name = "Max Mustermann", mxid = mxidA, trustLevel = TrustLevel.TL_1
        )
        val maxNord = RawPractitioner(
            id = "Practitioner/p2", name = "Max Gruber", mxid = mxidB, trustLevel = TrustLevel.TL_1
        )
        val nordAkhPerson = RawPractitioner(
            id = "Practitioner/p3", name = "Erika Akh", mxid = Mxid("@erika:example.com"), trustLevel = TrustLevel.TL_1
        )
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(maxAkh, maxNord, nordAkhPerson),
            practitionerRoles = listOf(
                RawPractitionerRole(
                    codeDisplay = "Arzt", practitionerReference = "Practitioner/p1",
                    organizationReference = "Organization/akh"
                ),
                RawPractitionerRole(
                    codeDisplay = "Arzt", practitionerReference = "Practitioner/p2",
                    organizationReference = "Organization/nord"
                ),
                RawPractitionerRole(
                    codeDisplay = "Arzt", practitionerReference = "Practitioner/p3",
                    organizationReference = "Organization/nord"
                )
            ),
            organizations = listOf(
                RawOrganization(id = "Organization/akh", name = "AKH Wien"),
                RawOrganization(id = "Organization/nord", name = "KH Nord")
            )
        )
        every { practitionersPort.search("mustermann akh") } returns bundle
        every { practitionerConsolidator.getOrganizationChain(any(), any(), any()) } answers {
            OrgChain(listOf(firstArg<RawOrganization>()))
        }
        every { practitionerConsolidator.buildDisplayName(any(), any()) } answers {
            val practitioner = firstArg<RawPractitioner>()
            val roleZip = secondArg<List<Pair<RawPractitionerRole, OrgChain>>>()
            "${practitioner.name} (${roleZip.map { it.first.codeDisplay }.joinToString()})"
        }

        val result = service.searchPractitioners("mustermann akh")

        // only Max Mustermann matches both "mustermann" (name) and "akh" (org chain)
        assertEquals(listOf("Practitioner/p1"), result.map { it["id"] })
    }

    @Test
    fun `different organization hides internal results`() {
        val internalPractitioner = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Mustermann, Internal",
            roles = listOf(),
            organizationChains = listOf(
                OrgChain(
                    listOf(
                        RawOrganization(
                            id = "Organization/doesnt-matter",
                            name = "doesnt-matter",
                            partOfReference = null,
                            alias = "doesnt-matter-alias"
                        )
                    )
                )
            ),
            visibility = Visibility.INTERNAL
        )
        val publicPractitioner = ConsolidatedUser(
            mxid = mxidB,
            displayName = "Mustermann, Public",
            roles = listOf(),
            organizationChains = listOf(
                OrgChain(
                    listOf(
                        RawOrganization(
                            id = "Organization/doesnt-matter",
                            name = "doesnt-matter",
                            partOfReference = null,
                            alias = "doesnt-matter-alias"
                        )
                    )
                )
            ),
            visibility = Visibility.PUBLIC
        )

        every { practitionersPort.search(any()) } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA), practitioner(mxidB)),
            practitionerRoles = listOf(),
            organizations = listOf()
        )
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        every { practitionerConsolidator.consolidate(any(), mxidA) } returns internalPractitioner
        every { practitionerConsolidator.consolidate(any(), mxidB) } returns publicPractitioner

        val result = service.search("mustermann", userMxid)

        assertTrue(
            result.results.size == 1 && result.results.first().displayName == "Mustermann, Public",
            "Only the Public user should pass the filtering"
        )
    }

    @Test
    fun `keeps only consolidated users matching every term`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA), practitioner(mxidB)),
            practitionerRoles = listOf(),
            organizations = listOf()
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns ConsolidatedUser(
            mxid = mxidA,
            displayName = "Max Mustermann",
            roles = listOf(),
            organizationChains = listOf(OrgChain(listOf(RawOrganization("Organization/akh", "AKH Wien")))),
            visibility = Visibility.PUBLIC
        )
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidB) } returns ConsolidatedUser(
            mxid = mxidB,
            displayName = "Max Gruber",
            roles = listOf(),
            organizationChains = listOf(OrgChain(listOf(RawOrganization("Organization/nord", "KH Nord")))),
            visibility = Visibility.PUBLIC
        )

        val result = service.search("mustermann akh", userMxid)

        // only Max Mustermann matches both terms: "mustermann" (displayName) and "akh" (org chain "AKH Wien")
        assertEquals(listOf(mxidA.value), result.results.map { it.userId })
    }

    // --- Visibility by mxid ------------------------------------------------

    @Test
    fun `visibilityOf returns the practitioner's visibility`() {
        val internal = Mxid("@internal:example.com")
        every { practitionersMxidPort.search(internal) } returns PractitionerSearchBundle(
            practitioners = listOf(
                RawPractitioner(id = "Practitioner/1", name = "Internal", mxid = internal, visibility = Visibility.INTERNAL)
            ),
            practitionerRoles = listOf(),
            organizations = listOf()
        )
        every { healthcareServicesMxidPort.search(internal) } returns
            HealthcareServiceSearchBundle(emptyList(), emptyList())

        assertEquals(Visibility.INTERNAL, service.visibilityOf(internal))
    }

    @Test
    fun `visibilityOf falls back to the healthcare service visibility`() {
        val serviceMxid = Mxid("@radio:example.com")
        every { practitionersMxidPort.search(serviceMxid) } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesMxidPort.search(serviceMxid) } returns HealthcareServiceSearchBundle(
            healthcareServices = listOf(
                RawHealthcareService(
                    id = "HealthcareService/1", name = "Radiologie",
                    mxid = serviceMxid, organizationReference = null, visibility = Visibility.INTERNAL
                )
            ),
            organizations = listOf()
        )

        assertEquals(Visibility.INTERNAL, service.visibilityOf(serviceMxid))
    }

    @Test
    fun `visibilityOf returns null when no entry carries the mxid`() {
        val unknown = Mxid("@nobody:example.com")
        every { practitionersMxidPort.search(unknown) } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesMxidPort.search(unknown) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())

        assertNull(service.visibilityOf(unknown))
    }

    @Test
    fun `excludes users whose mxid is on the organization blocked list`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns user
        every { blockedUserPort.isBlocked(mxidA) } returns true

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `excludes users carrying a blocked receive role`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(
                RawPractitionerRole(
                    codes = listOf(RoleCode("https://roles.example", "cardio", "Cardio"))
                )
            ),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns user
        every { blockedRolePort.isAnyRoleBlocked(setOf("https://roles.example|cardio"), InvitationDirection.INBOUND) } returns true

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `keeps users whose role is not blocked`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(
                RawPractitionerRole(
                    codes = listOf(RoleCode("https://roles.example", "cardio", "Cardio"))
                )
            ),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns user
        every { blockedRolePort.isAnyRoleBlocked(setOf("https://roles.example|cardio"), InvitationDirection.INBOUND) } returns false

        val result = service.search("alice", userMxid)

        assertEquals(listOf(mxidA.value), result.results.map { it.userId })
    }

    @Test
    fun `excludes healthcare services whose mxid is on the organization blocked list`() {
        val healthcareBundle = HealthcareServiceSearchBundle(
            healthcareServices = listOf(healthcareService(mxidB)),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidB,
            displayName = "Bob Service",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns
                PractitionerSearchBundle(emptyList(), emptyList(), emptyList())
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns healthcareBundle
        every { healthcareServiceConsolidator.consolidate(healthcareBundle, mxidB) } returns user
        every { blockedUserPort.isBlocked(mxidB) } returns true

        val result = service.search("bob", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `returns empty results when the current user is on the organization blocked list`() {
        every { blockedUserPort.isBlocked(userMxid) } returns true

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
        verify(exactly = 0) { practitionersPort.search(any()) }
    }

    @Test
    fun `returns empty results when the current user carries a blocked send role`() {
        every { practitionerConsolidator.consolidate(any(), userMxid) } returns ConsolidatedUser(
            mxid = userMxid,
            displayName = "Max",
            roles = listOf(
                RawPractitionerRole(
                    codes = listOf(RoleCode("https://roles.example", "cardio", "Cardio"))
                )
            ),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { blockedRolePort.isAnyRoleBlocked(setOf("https://roles.example|cardio"), InvitationDirection.OUTBOUND) } returns true

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
        verify(exactly = 0) { practitionersPort.search(any()) }
    }

    @Test
    fun `allows the search when the current user carries a non-blocked role`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val foundUser = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionerConsolidator.consolidate(any(), userMxid) } returns ConsolidatedUser(
            mxid = userMxid,
            displayName = "Max",
            roles = listOf(
                RawPractitionerRole(
                    codes = listOf(RoleCode("https://roles.example", "cardio", "Cardio"))
                )
            ),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns foundUser
        every { blockedRolePort.isAnyRoleBlocked(setOf("https://roles.example|cardio"), InvitationDirection.OUTBOUND) } returns false

        val result = service.search("alice", userMxid)

        assertEquals(listOf(mxidA.value), result.results.map { it.userId })
    }

    @Test
    fun `returns empty results when the current user's send right is disabled`() {
        every { userInvitationRightsPort.getPractitionerRights(userMxid) } returns
                InvitationRightsEntry(id = "1", name = "Max", mxid = userMxid, receive = true, send = false)

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
        verify(exactly = 0) { practitionersPort.search(any()) }
    }

    @Test
    fun `excludes users whose receive right is disabled`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns user
        every { userInvitationRightsPort.getPractitionerRights(mxidA) } returns
                InvitationRightsEntry(id = "1", name = "Alice", mxid = mxidA, receive = false, send = true)

        val result = service.search("alice", userMxid)

        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `keeps users whose receive right is enabled`() {
        val practitionerBundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner(mxidA)),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )
        val user = ConsolidatedUser(
            mxid = mxidA,
            displayName = "Alice",
            roles = listOf(),
            organizationChains = listOf(),
            visibility = Visibility.PUBLIC
        )
        every { practitionersPort.search(any()) } returns practitionerBundle
        every { healthcareServicesPort.searchHealthcareServices(any()) } returns
                HealthcareServiceSearchBundle(emptyList(), emptyList())
        every { practitionerConsolidator.consolidate(practitionerBundle, mxidA) } returns user
        every { userInvitationRightsPort.getPractitionerRights(mxidA) } returns
                InvitationRightsEntry(id = "1", name = "Alice", mxid = mxidA, receive = true, send = true)

        val result = service.search("alice", userMxid)

        assertEquals(listOf(mxidA.value), result.results.map { it.userId })
    }
}
