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

import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PractitionerConsolidatorTest {

    private val findOrganizationById = mock<FindOrganizationByIdPort>()
    private val consolidator = PractitionerConsolidator(findOrganizationById, AuditLogger())

    private val mxid = Mxid("@m.mustermann:pgk.akh.at")

    private fun practitioner(id: String = "Practitioner/1") = RawPractitioner(
        id = id,
        name = "Dr. Mustermann, Max",
        trustLevel = TrustLevel.TL_2,
        mxid = mxid
    )

    private fun role(
        practitionerRef: String = "Practitioner/1",
        code: String = "Oberarzt",
        orgRef: String? = "Organization/1"
    ) = RawPractitionerRole(
        codeDisplay = code,
        practitionerReference = practitionerRef,
        trustLevel = TrustLevel.TL_1,
        organizationReference = orgRef
    )

    private fun org(id: String = "Organization/1", name: String = "Kardiologie", partOf: String? = null) =
        RawOrganization(id = id, name = name, partOfReference = partOf)

    @Test
    fun `consolidates a practitioner with role and organization`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(role()),
            organizations = listOf(org())
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(mxid, result?.mxid)
        assertEquals("Dr. Mustermann, Max (Oberarzt, Kardiologie)", result?.displayName)
        assertEquals(listOf("Oberarzt"), result?.roleNames)
        assertEquals("Kardiologie", result?.rootOrganization?.name)
    }

    @Test
    fun `merges role codes into a single parenthetical`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(
                role(code = "Oberarzt", orgRef = "Organization/1"),
                role(code = "Ordi", orgRef = "Organization/2")
            ),
            organizations = listOf(
                org("Organization/1", "Kardiologie"),
                org("Organization/2", "Ambulanz")
            )
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(
            "Dr. Mustermann, Max (Oberarzt, Ordi, Kardiologie, Ambulanz)",
            result?.displayName
        )
    }

    @Test
    fun `uses only the practitioner name when there are no roles`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals("Dr. Mustermann, Max", result?.displayName)
    }

    @Test
    fun `walks the organization chain for a role`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(role(orgRef = "Organization/1")),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                org("Organization/9", "AKH")
            )
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(
            "Dr. Mustermann, Max (Oberarzt, Kardiologie AKH)",
            result?.displayName
        )
    }

    @Test
    fun `returns null when no practitioner matches the mxid`() {
        val bundle = PractitionerSearchBundle(
            practitioners = emptyList(),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertNull(result)
    }

    @Test
    fun `returns null when practitioner mxid differs from requested mxid`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner().copy(mxid = Mxid("@someone.else:other.org"))),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertNull(result)
    }

    @Test
    fun `handles role with dangling organization reference`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(role(orgRef = "Organization/missing")),
            organizations = emptyList()
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals("Dr. Mustermann, Max (Oberarzt)", result?.displayName)
    }

    @Test
    fun `merges role codes with a single organization chain`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(
                role(code = "Oberarzt", orgRef = "Organization/1"),
                role(code = "Leitung Schrittmacherambulanz", orgRef = "Organization/1")
            ),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                org("Organization/9", "AKH Wien")
            )
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(
            "Dr. Mustermann, Max (Oberarzt, Leitung Schrittmacherambulanz, Kardiologie AKH Wien)",
            result?.displayName
        )
    }

    @Test
    fun `looks up missing parent organizations by id`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(role(code = "Leitung Schrittmacherambulanz", orgRef = "Organization/1")),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9")
            )
        )

        whenever(findOrganizationById.findById("9"))
            .thenReturn(org("Organization/9", "Innere Med II", partOf = "Organization/99"))
        whenever(findOrganizationById.findById("99"))
            .thenReturn(org("Organization/99", "AKH Wien"))

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(
            "Dr. Mustermann, Max (Leitung Schrittmacherambulanz, Kardiologie Innere Med II AKH Wien)",
            result?.displayName
        )
    }

    @Test
    fun `deduplicates organization chains that share the same root organization`() {
        val bundle = PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(
                role(code = "Oberarzt", orgRef = "Organization/1"),
                role(code = "Leitung", orgRef = "Organization/2")
            ),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                org("Organization/9", "AKH Wien"),
                org("Organization/2", "Innere Med II", partOf = "Organization/9")
            )
        )

        val result = consolidator.consolidate(bundle, mxid)

        assertEquals(
            "Dr. Mustermann, Max (Oberarzt, Leitung, Kardiologie AKH Wien)",
            result?.displayName
        )
    }

    @Test
    fun `builds the chat display name without the user roles`() {
        val roleZip = listOf(
            role(code = "Oberarzt", orgRef = "Organization/1") to OrgChain(
                listOf(
                    org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                    org("Organization/9", "AKH Wien")
                )
            ),
            role(code = "Leitung", orgRef = "Organization/2") to OrgChain(
                listOf(
                    org("Organization/2", "Innere Med II", partOf = "Organization/9"),
                    org("Organization/9", "AKH Wien")
                )
            )
        )

        val displayName = consolidator.buildDisplayName(practitioner(), roleZip, includeRoles = false)

        assertEquals("Dr. Mustermann, Max (Kardiologie AKH Wien)", displayName)
    }
}
