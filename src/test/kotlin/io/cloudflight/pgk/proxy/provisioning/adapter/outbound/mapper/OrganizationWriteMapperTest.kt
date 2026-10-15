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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Reference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrganizationWriteMapperTest {

    private val mapper = OrganizationWriteMapper()

    private val abteilung = OrganizationType("300", "Allgemeine Krankenanstalt")
    private val institut = OrganizationType("502", "Primärversorgungseinheit")
    private val ambulanz = OrganizationType("304", "Selbstständiges Ambulatorium")

    @Test
    fun `toFhir maps name partOf and type`() {
        val fhir = mapper.toFhir(
            OrganizationData(
                id = "org-1",
                name = "Abteilung Anästhesiologie",
                parentId = "org-root",
                type = abteilung
            )
        )

        assertEquals("org-1", fhir.idElement.idPart)
        assertTrue(fhir.active)
        assertEquals("Abteilung Anästhesiologie", fhir.name)
        assertEquals("Organization/org-root", fhir.partOf.reference)
        assertEquals(OrganizationType.SYSTEM, fhir.type.single().coding.single().system)
        assertEquals("300", fhir.type.single().coding.single().code)
        assertEquals("Allgemeine Krankenanstalt", fhir.type.single().coding.single().display)
    }

    @Test
    fun `toFhir leaves partOf empty when no parent is set`() {
        val fhir = mapper.toFhir(OrganizationData(name = "Klinik Favoriten"))

        assertFalse(fhir.hasPartOf())
        assertTrue(fhir.type.isEmpty())
    }

    @Test
    fun `toDomain maps a FHIR organization back to the domain model`() {
        val domain = mapper.toDomain(
            Organization().apply {
                setId("org-1")
                name = "Institut für Hygiene"
                partOf = Reference("Organization/org-root")
                addType(
                    CodeableConcept().addCoding(Coding(OrganizationType.SYSTEM, "502", "Primärversorgungseinheit"))
                )
            }
        )

        assertEquals("org-1", domain.id)
        assertEquals("Institut für Hygiene", domain.name)
        assertEquals("org-root", domain.parentId)
        assertEquals(institut, domain.type)
    }

    @Test
    fun `toDomain returns a null type for organizations without a type`() {
        val domain = mapper.toDomain(Organization().apply {
            setId("org-root")
            name = "Klinik Favoriten"
        })

        assertEquals("org-root", domain.id)
        assertNull(domain.type)
        assertNull(domain.parentId)
    }

    @Test
    fun `mergeInto keeps unmanaged fields and updates the managed ones`() {
        val existing = Organization().apply {
            setId("org-1")
            name = "Alt"
            addIdentifier(Identifier().setSystem("https://x").setValue("y"))
        }

        val merged = mapper.mergeInto(
            existing,
            OrganizationData(
                id = "org-1", name = "Neu", parentId = "org-2", type = ambulanz
            )
        )

        assertEquals("Neu", merged.name)
        assertEquals("Organization/org-2", merged.partOf.reference)
        assertEquals("304", merged.type.single().coding.single().code)
        assertEquals("https://x", merged.identifier.single().system)
        assertTrue(merged.active)
    }

    @Test
    fun `mergeInto clears the type when the organization has no type`() {
        val existing = Organization().apply {
            setId("org-1")
            name = "Alt"
            addType(
                CodeableConcept().addCoding(Coding(OrganizationType.SYSTEM, "300", "Allgemeine Krankenanstalt"))
            )
        }

        val merged = mapper.mergeInto(existing, OrganizationData(id = "org-1", name = "Alt"))

        assertTrue(merged.type.isEmpty())
    }
}
