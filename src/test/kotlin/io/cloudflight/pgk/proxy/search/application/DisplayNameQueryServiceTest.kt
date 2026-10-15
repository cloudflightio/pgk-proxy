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

import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.FindProvisionedPractitionersPort
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DisplayNameQueryServiceTest {

    private val findPractitioners = mockk<FindProvisionedPractitionersPort>()
    private val findOrganizationById = mockk<FindOrganizationByIdPort>()
    private val consolidator = PractitionerConsolidator(findOrganizationById, AuditLogger())
    private val service = DisplayNameQueryService(findPractitioners, findOrganizationById, consolidator, AuditLogger())

    private val mxid = Mxid("@max:matrix.traefik.me")

    private fun practitioner(id: String = "Practitioner/1") = RawPractitioner(
        id = id,
        name = "Dr. Mustermann, Max",
        trustLevel = TrustLevel.TL_2,
        mxid = mxid
    )

    private fun role(
        practitionerRef: String = "Practitioner/1",
        code: String = "Oberarzt",
        orgRef: String = "Organization/1"
    ) = RawPractitionerRole(
        codeDisplay = code,
        practitionerReference = practitionerRef,
        organizationReference = orgRef
    )

    private fun org(id: String = "Organization/1", name: String = "Kardiologie", partOf: String? = null) =
        RawOrganization(id = id, name = name, partOfReference = partOf)

    @Test
    fun `maps every practitioner to its chat display name without roles`() {
        every { findPractitioners.findProvisioned() } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(role()),
            organizations = listOf(
                org("Organization/1", "Kardiologie", partOf = "Organization/9"),
                org("Organization/9", "AKH Wien")
            )
        )

        assertEquals(
            mapOf(mxid to "Dr. Mustermann, Max (Kardiologie AKH Wien)"),
            service.queryDisplayNames()
        )
    }

    @Test
    fun `skips practitioners without an mxid`() {
        every { findPractitioners.findProvisioned() } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner().copy(mxid = null), practitioner(id = "Practitioner/2")),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        assertEquals(
            mapOf(mxid to "Dr. Mustermann, Max"),
            service.queryDisplayNames()
        )
    }

    @Test
    fun `uses only the practitioner name when no role exists`() {
        every { findPractitioners.findProvisioned() } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = emptyList(),
            organizations = emptyList()
        )

        assertEquals(mapOf(mxid to "Dr. Mustermann, Max"), service.queryDisplayNames())
    }

    @Test
    fun `keeps multiple organizations of a practitioner`() {
        every { findPractitioners.findProvisioned() } returns PractitionerSearchBundle(
            practitioners = listOf(practitioner()),
            practitionerRoles = listOf(
                role(code = "Oberarzt", orgRef = "Organization/1"),
                role(code = "Leitung", orgRef = "Organization/2")
            ),
            organizations = listOf(
                org("Organization/1", "Kardiologie"),
                org("Organization/2", "Ambulanz")
            )
        )

        assertEquals(
            mapOf(mxid to "Dr. Mustermann, Max (Kardiologie, Ambulanz)"),
            service.queryDisplayNames()
        )
    }
}
