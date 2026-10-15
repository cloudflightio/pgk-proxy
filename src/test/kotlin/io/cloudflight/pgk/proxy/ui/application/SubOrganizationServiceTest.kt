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

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.cloudflight.pgk.proxy.provisioning.port.CreateOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationByIdQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationTypeCatalogPort
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationsByParentQueryPort
import io.cloudflight.pgk.proxy.provisioning.port.RemoveOrganizationPort
import io.cloudflight.pgk.proxy.provisioning.port.UpdateOrganizationPort
import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubOrganizationServiceTest {

    private val roleManagement = mockk<RoleManagementService>()
    private val organizationTypeCatalog = mockk<OrganizationTypeCatalogPort>()
    private val organizationQuery = mockk<OrganizationQueryPort>()
    private val organizationByIdQuery = mockk<OrganizationByIdQueryPort>()
    private val organizationsByParentQuery = mockk<OrganizationsByParentQueryPort>()
    private val createOrganization = mockk<CreateOrganizationPort>()
    private val updateOrganization = mockk<UpdateOrganizationPort>(relaxed = true)
    private val removeOrganization = mockk<RemoveOrganizationPort>(relaxed = true)

    private val service = SubOrganizationService(
        roleManagement, organizationTypeCatalog, organizationQuery, organizationByIdQuery,
        organizationsByParentQuery, createOrganization, updateOrganization, removeOrganization
    )

    private val root = OrganizationData(id = "o-root", name = "Klinik Favoriten")
    private val abteilung = OrganizationData(
        id = "o-1", name = "Abteilung Anästhesiologie", parentId = "o-root",
        type = OrganizationType("300", "Allgemeine Krankenanstalt")
    )
    private val andereKlinik = OrganizationData(id = "o-x", name = "Klinik Hietzing")

    // --- Directory tree -----------------------------------------------------

    @Test
    fun `directoryTree builds a forest from every organization in the directory`() {
        every { organizationQuery.getOrganizations() } returns listOf(root, abteilung, andereKlinik)

        val tree = service.directoryTree()

        assertEquals(listOf("Klinik Favoriten", "Klinik Hietzing"), tree.map { it.name })
        val favoriten = tree.single { it.name == "Klinik Favoriten" }
        assertEquals(1, favoriten.children.size)
        assertEquals("Abteilung Anästhesiologie", favoriten.children.single().name)
        assertTrue(favoriten.children.single().children.isEmpty())
        assertTrue(tree.single { it.name == "Klinik Hietzing" }.children.isEmpty())
    }

    @Test
    fun `directoryTree treats a dangling parent as a root and drops pure cycles`() {
        val orphan = OrganizationData(id = "o-orphan", name = "Fremd", parentId = "o-missing")
        val loopA = OrganizationData(id = "o-a", name = "A", parentId = "o-b")
        val loopB = OrganizationData(id = "o-b", name = "B", parentId = "o-a")
        every { organizationQuery.getOrganizations() } returns listOf(orphan, loopA, loopB)

        val tree = service.directoryTree()

        assertEquals(listOf("Fremd"), tree.map { it.name })
        assertTrue(tree.single().children.isEmpty())
    }

    // --- Type options -------------------------------------------------------

    @Test
    fun `typeOptions delegates to the organization type catalog`() {
        val types = listOf(OrganizationType("300", "Allgemeine Krankenanstalt"))
        every { organizationTypeCatalog.loadTypes() } returns types

        assertEquals(types, service.typeOptions())
    }

    // --- Create -------------------------------------------------------------

    @Test
    fun `create delegates to the provisioning port after validating the parent`() {
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { createOrganization.create(any()) } returns "o-2"

        val id = service.create("Institut für Hygiene", "o-root", OrganizationType("502", "Primärversorgungseinheit"))

        assertEquals("o-2", id)
        verify {
            createOrganization.create(
                OrganizationData(name = "Institut für Hygiene", parentId = "o-root", type = OrganizationType("502", "Primärversorgungseinheit"))
            )
        }
    }

    @Test
    fun `create rejects a blank parent`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.create("Fremd", "", null)
        }
        verify(exactly = 0) { createOrganization.create(any()) }
    }

    @Test
    fun `create rejects a parent that does not exist`() {
        every { organizationByIdQuery.getOrganization("o-missing") } returns null

        assertThrows(IllegalArgumentException::class.java) {
            service.create("Fremd", "o-missing", null)
        }
        verify(exactly = 0) { createOrganization.create(any()) }
    }

    // --- Update -------------------------------------------------------------

    @Test
    fun `update delegates to the provisioning port with the new parent`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { organizationsByParentQuery.getOrganizations("o-1") } returns emptyList()

        service.update("o-1", "Abteilung Anästhesie", "o-root", OrganizationType("303", "Sanatorium"))

        verify {
            updateOrganization.update(
                OrganizationData(id = "o-1", name = "Abteilung Anästhesie", parentId = "o-root", type = OrganizationType("303", "Sanatorium"))
            )
        }
    }

    @Test
    fun `update with an empty parent turns the organization into a root`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung

        service.update("o-1", "Abteilung Anästhesie", "", OrganizationType("303", "Sanatorium"))

        verify {
            updateOrganization.update(
                OrganizationData(id = "o-1", name = "Abteilung Anästhesie", parentId = null, type = OrganizationType("303", "Sanatorium"))
            )
        }
    }

    @Test
    fun `update rejects an unknown organization`() {
        every { organizationByIdQuery.getOrganization("o-foreign") } returns null

        assertThrows(IllegalArgumentException::class.java) {
            service.update("o-foreign", "Fremd", "o-root", null)
        }
        verify(exactly = 0) { updateOrganization.update(any()) }
    }

    @Test
    fun `update rejects reparenting to a descendant`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-2") } returns
            OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1")
        every { organizationsByParentQuery.getOrganizations("o-1") } returns
            listOf(OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1"))
        every { organizationsByParentQuery.getOrganizations("o-2") } returns emptyList()

        assertThrows(IllegalArgumentException::class.java) {
            service.update("o-1", "Abteilung Anästhesie", "o-2", null)
        }
        verify(exactly = 0) { updateOrganization.update(any()) }
    }

    // --- Delete -------------------------------------------------------------

    @Test
    fun `delete delegates to the provisioning port when the organization has no children`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { organizationsByParentQuery.getOrganizations("o-1") } returns emptyList()

        service.delete("o-1")

        verify { removeOrganization.remove("o-1") }
    }

    @Test
    fun `delete rejects an organization with children`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { organizationsByParentQuery.getOrganizations("o-1") } returns listOf(
            OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1")
        )

        assertThrows(IllegalArgumentException::class.java) { service.delete("o-1") }
        verify(exactly = 0) { removeOrganization.remove(any()) }
    }

    @Test
    fun `delete rejects a root organization`() {
        every { organizationByIdQuery.getOrganization("o-root") } returns root

        assertThrows(IllegalArgumentException::class.java) { service.delete("o-root") }
        verify(exactly = 0) { removeOrganization.remove(any()) }
    }

    @Test
    fun `delete rejects an organization whose parent is missing from the directory`() {
        val orphan = OrganizationData(id = "o-orphan", name = "Fremd", parentId = "o-missing")
        every { organizationByIdQuery.getOrganization("o-orphan") } returns orphan
        every { organizationByIdQuery.getOrganization("o-missing") } returns null

        assertThrows(IllegalArgumentException::class.java) { service.delete("o-orphan") }
        verify(exactly = 0) { removeOrganization.remove(any()) }
    }

    // --- Three-level limit --------------------------------------------------

    @Test
    fun `directoryTree truncates deeper than three levels`() {
        every { organizationQuery.getOrganizations() } returns listOf(
            OrganizationData(id = "o-root", name = "Klinik Favoriten"),
            OrganizationData(id = "o-1", name = "Abteilung Anästhesiologie", parentId = "o-root"),
            OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1"),
            OrganizationData(id = "o-3", name = "Station", parentId = "o-2")
        )

        val tree = service.directoryTree()

        val root = tree.single()
        val ebene1 = root.children.single()
        val ebene2 = ebene1.children.single()
        assertEquals("Unterabteilung", ebene2.name)
        assertTrue(ebene2.children.isEmpty())
    }

    @Test
    fun `create allows a parent on the second level`() {
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { createOrganization.create(any()) } returns "o-2"

        service.create("Unterabteilung", "o-1", null)

        verify {
            createOrganization.create(OrganizationData(name = "Unterabteilung", parentId = "o-1", type = null))
        }
    }

    @Test
    fun `create rejects a parent on the third level`() {
        val unterabteilung = OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1")
        every { organizationByIdQuery.getOrganization("o-2") } returns unterabteilung
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-root") } returns root

        assertThrows(IllegalArgumentException::class.java) {
            service.create("Ambulanz", "o-2", null)
        }
        verify(exactly = 0) { createOrganization.create(any()) }
    }

    @Test
    fun `update allows moving deeper within three levels`() {
        val andereSub = OrganizationData(id = "o-y", name = "Andere Abteilung", parentId = "o-root")
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-y") } returns andereSub
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { organizationsByParentQuery.getOrganizations("o-1") } returns emptyList()

        service.update("o-1", "Abteilung Anästhesie", "o-y", null)

        verify {
            updateOrganization.update(
                OrganizationData(id = "o-1", name = "Abteilung Anästhesie", parentId = "o-y", type = null)
            )
        }
    }

    @Test
    fun `update rejects moving deeper than three levels`() {
        val unterabteilung = OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1")
        val andereSub = OrganizationData(id = "o-y", name = "Andere Abteilung", parentId = "o-root")
        every { organizationByIdQuery.getOrganization("o-1") } returns abteilung
        every { organizationByIdQuery.getOrganization("o-y") } returns andereSub
        every { organizationByIdQuery.getOrganization("o-root") } returns root
        every { organizationsByParentQuery.getOrganizations("o-1") } returns listOf(unterabteilung)
        every { organizationsByParentQuery.getOrganizations("o-2") } returns emptyList()

        assertThrows(IllegalArgumentException::class.java) {
            service.update("o-1", "Abteilung Anästhesie", "o-y", null)
        }
        verify(exactly = 0) { updateOrganization.update(any()) }
    }

    @Test
    fun `searchParentCandidates excludes organizations on the deepest level`() {
        val unterabteilung = OrganizationData(id = "o-2", name = "Unterabteilung", parentId = "o-1")
        every { organizationQuery.getOrganizations() } returns listOf(root, abteilung, unterabteilung)
        every { roleManagement.searchOrganizations("Unter") } returns listOf(
            OrganizationOption("Organization/o-root", "Klinik Favoriten"),
            OrganizationOption("Organization/o-1", "Klinik Favoriten – Abteilung Anästhesiologie"),
            OrganizationOption("Organization/o-2", "Klinik Favoriten – Abteilung Anästhesiologie – Unterabteilung")
        )

        val candidates = service.searchParentCandidates("Unter")

        assertEquals(listOf("Organization/o-root", "Organization/o-1"), candidates.map { it.id })
    }
}
