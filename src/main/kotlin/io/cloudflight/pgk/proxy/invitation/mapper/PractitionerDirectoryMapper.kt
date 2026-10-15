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
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

/**
 * Maps a FHIR [Practitioner] to its directory entry (identity only — the mxid is read
 * from the telecom contact points when present). No rights are decoded here.
 */
@Component
class PractitionerDirectoryMapper {

    fun toEntry(practitioner: Practitioner): PractitionerDirectoryEntry {
        val name = practitioner.name.firstOrNull()
        val given = name?.given?.joinToString(" ") { it.value }.orEmpty()
        val family = name?.family.orEmpty()
        return PractitionerDirectoryEntry(
            id = practitioner.idElement.idPart,
            name = listOf(given, family).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unbekannt" },
            mxid = practitioner.telecom.firstNotNullOfOrNull { contactPoint ->
                MxidTelecom.extractMxid(contactPoint.value)
            }
        )
    }
}
