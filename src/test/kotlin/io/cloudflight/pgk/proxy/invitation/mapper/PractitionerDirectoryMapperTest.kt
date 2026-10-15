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

package io.cloudflight.pgk.proxy.invitation.mapper

import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerDirectoryEntry
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Practitioner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PractitionerDirectoryMapperTest {

    private val mapper = PractitionerDirectoryMapper()

    @Test
    fun `extracts name and mxid`() {
        val practitioner = Practitioner().apply {
            id = "p-1"
            addName().apply {
                addGiven("Anna")
                addGiven("Maria")
                family = "Berger"
            }
            addTelecom().apply {
                system = ContactPoint.ContactPointSystem.URL
                value = MxidTelecom.toTelecomValue(Mxid("@anna:matrix.traefik.me"))
            }
        }

        assertEquals(
            PractitionerDirectoryEntry(
                id = "p-1",
                name = "Anna Maria Berger",
                mxid = Mxid("@anna:matrix.traefik.me")
            ),
            mapper.toEntry(practitioner)
        )
    }

    @Test
    fun `falls back to Unbekannt and no mxid when absent`() {
        val practitioner = Practitioner().apply { id = "p-2" }

        val entry = mapper.toEntry(practitioner)

        assertEquals("Unbekannt", entry.name)
        assertNull(entry.mxid)
    }
}
