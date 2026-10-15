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

package io.cloudflight.pgk.proxy.ui.application

import io.cloudflight.pgk.proxy.search.api.domain.model.ExistingRoleRowOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCodeOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleRemoval
import io.cloudflight.pgk.proxy.search.api.port.ApplyRoleChangesPort
import io.cloudflight.pgk.proxy.search.api.port.RoleCatalogPort
import io.cloudflight.pgk.proxy.shared.events.PractitionerRolesLoadFailedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class PractitionerRoleAssignmentServiceTest {

    private val roleManagement = mockk<RoleManagementService>()
    private val invitationRights = mockk<InvitationRightsService>()
    private val roleCatalog = mockk<RoleCatalogPort>()
    private val applyRoleChanges = mockk<ApplyRoleChangesPort>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val service = PractitionerRoleAssignmentService(
        roleManagement, invitationRights, roleCatalog, applyRoleChanges, eventPublisher
    )

    private val fachAerzte = "https://termgit.elga.gv.at/CodeSystem/elga-fachaerzte"
    private val gtelVo = "https://termgit.elga.gv.at/CodeSystem/elga-gtelvogdarollen"

    private val catalogRoles = listOf(
        RoleCatalogEntry(fachAerzte, "106", "Fachärztin/Facharzt für Chirurgie"),
        RoleCatalogEntry(fachAerzte, "108", "Fachärztin/Facharzt für Innere Medizin"),
        RoleCatalogEntry(gtelVo, "100", "Ärztin/Arzt für Allgemeinmedizin"),
        RoleCatalogEntry(gtelVo, "212", "Diplomierte Gesundheits- und Krankenschwester")
    )

    // --- Practitioner list --------------------------------------------------

    @Test
    fun `practitioner list filters by name and maps all roles`() {
        every { invitationRights.listPractitioners() } returns listOf(
            InvitationRightsRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", false, false),
            InvitationRightsRow("2", "Dr. Thomas Maier", "@thomas:matrix.pgk.example", "DT", false, false)
        )
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Fachärztin/Facharzt für Chirurgie", removalKey = "role-1|$fachAerzte|106"))
        )
        every { roleManagement.findExistingRoles("2") } returns emptyList()

        val result = service.listPractitioners("anna")

        assertEquals(listOf("1"), result.map { it.id })
        val row = result.single()
        assertEquals("Dr. Anna Berger", row.name)
        assertEquals("AB", row.initials)
        assertEquals(1, row.roles.size)
        assertEquals("Fachärztin/Facharzt für Chirurgie", row.roles.single().display)
    }

    @Test
    fun `practitioner role chips show the assigned roles without duplicates`() {
        every { invitationRights.listPractitioners() } returns listOf(
            InvitationRightsRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", false, false)
        )
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$gtelVo|100", "Ärztin/Arzt für Allgemeinmedizin", removalKey = "role-1|$gtelVo|100")),
            existingRow("role-2", RoleCodeOption("$gtelVo|100", "Ärztin/Arzt für Allgemeinmedizin", removalKey = "role-2|$gtelVo|100"))
        )

        val chip = service.listPractitioners().single().roles.single()

        assertEquals("Ärztin/Arzt für Allgemeinmedizin", chip.display)
    }

    @Test
    fun `duplicate directory entries of the same practitioner are listed once`() {
        every { invitationRights.listPractitioners() } returns listOf(
            InvitationRightsRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", false, false),
            InvitationRightsRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", false, false)
        )
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Fachärztin/Facharzt für Chirurgie", removalKey = "role-1|$fachAerzte|106"))
        )

        val result = service.listPractitioners()

        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun `practitioner role chips publish a load-failed event when existing roles cannot be loaded`() {
        every { invitationRights.listPractitioners() } returns listOf(
            InvitationRightsRow("1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", false, false)
        )
        every { roleManagement.findExistingRoles("1") } throws IllegalStateException("boom")

        val rows = service.listPractitioners()

        assertEquals(1, rows.size)
        assertTrue(rows.single().roles.isEmpty())
        verify { eventPublisher.publishEvent(any<PractitionerRolesLoadFailedEvent>()) }
    }

    // --- Role options ------------------------------------------------------

    @Test
    fun `role options list all non-retired catalog roles with the assigned checked state`() {
        every { roleCatalog.loadEntries() } returns catalogRoles
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Fachärztin/Facharzt für Chirurgie"))
        )

        val options = service.roleOptions("1", "", null)

        assertEquals(
            setOf("$fachAerzte|106", "$fachAerzte|108", "$gtelVo|100", "$gtelVo|212"),
            options.map { it.token }.toSet()
        )
        assertTrue(options.first { it.token == "$fachAerzte|106" }.checked)
        assertEquals(false, options.first { it.token == "$fachAerzte|108" }.checked)
    }

    @Test
    fun `role options keep the in-progress checked state while filtering`() {
        every { roleCatalog.loadEntries() } returns catalogRoles

        val options = service.roleOptions("1", "Krankenschwester", setOf("$gtelVo|212"))

        assertEquals(listOf("$gtelVo|212"), options.map { it.token })
        assertTrue(options.single().checked)
    }

    @Test
    fun `role options are empty when the query matches nothing`() {
        every { roleCatalog.loadEntries() } returns catalogRoles
        every { roleManagement.findExistingRoles("1") } returns emptyList()

        val options = service.roleOptions("1", "xyz", null)

        assertTrue(options.isEmpty())
    }

    // --- Apply --------------------------------------------------------------

    @Test
    fun `apply adds newly checked roles and removes unchecked existing roles`() {
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Chirurgie", removalKey = "role-1|$fachAerzte|106")),
            existingRow("role-2", RoleCodeOption("$gtelVo|100", "Allgemeinmedizin", removalKey = "role-2|$gtelVo|100"))
        )
        every { applyRoleChanges.applyRoleChanges(any(), any(), any(), any()) } returns Unit

        val message = service.applyAssignment(
            "1", "o-kardio", listOf("$fachAerzte|106", "$gtelVo|212")
        )

        assertEquals("Rollen erfolgreich aktualisiert", message)
        verify { applyRoleChanges.applyRoleChanges("1", "o-kardio", listOf("$gtelVo|212"), listOf(RoleRemoval("role-2", "$gtelVo|100"))) }
    }

    @Test
    fun `apply returns null when the checked state is unchanged`() {
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Chirurgie", removalKey = "role-1|$fachAerzte|106"))
        )

        val message = service.applyAssignment("1", "o-kardio", listOf("$fachAerzte|106"))

        assertNull(message)
        verify(exactly = 0) { applyRoleChanges.applyRoleChanges(any(), any(), any(), any()) }
    }

    @Test
    fun `apply with an empty selection removes every existing role`() {
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Chirurgie", removalKey = "role-1|$fachAerzte|106")),
            existingRow("role-2", RoleCodeOption("$gtelVo|100", "Allgemeinmedizin", removalKey = "role-2|$gtelVo|100"))
        )
        every { applyRoleChanges.applyRoleChanges(any(), any(), any(), any()) } returns Unit

        val message = service.applyAssignment("1", "o-kardio", emptyList())

        assertEquals("Rollen erfolgreich entfernt", message)
        verify {
            applyRoleChanges.applyRoleChanges(
                "1", "o-kardio", emptyList(),
                listOf(RoleRemoval("role-1", "$fachAerzte|106"), RoleRemoval("role-2", "$gtelVo|100"))
            )
        }
    }

    // --- Quick remove -------------------------------------------------------

    @Test
    fun `removeRole removes only the requested role`() {
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Chirurgie", removalKey = "role-1|$fachAerzte|106")),
            existingRow("role-2", RoleCodeOption("$gtelVo|100", "Allgemeinmedizin", removalKey = "role-2|$gtelVo|100"))
        )
        every { applyRoleChanges.applyRoleChanges(any(), any(), any(), any()) } returns Unit

        val removed = service.removeRole("1", "$gtelVo|100")

        assertTrue(removed)
        verify {
            applyRoleChanges.applyRoleChanges("1", "", emptyList(), listOf(RoleRemoval("role-2", "$gtelVo|100")))
        }
    }

    @Test
    fun `removeRole reports nothing to remove for an unassigned role`() {
        every { roleManagement.findExistingRoles("1") } returns listOf(
            existingRow("role-1", RoleCodeOption("$fachAerzte|106", "Chirurgie", removalKey = "role-1|$fachAerzte|106"))
        )

        val removed = service.removeRole("1", "$gtelVo|100")

        assertEquals(false, removed)
        verify(exactly = 0) { applyRoleChanges.applyRoleChanges(any(), any(), any(), any()) }
    }

    private fun existingRow(practitionerRoleId: String, vararg codes: RoleCodeOption): ExistingRoleRowOption =
        ExistingRoleRowOption(practitionerRoleId, "Klinik Favoriten", codes.toList())
}
