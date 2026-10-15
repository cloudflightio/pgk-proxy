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

package io.cloudflight.pgk.proxy.search.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.Visibility
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

/**
 * Maps a HAPI [Practitioner] into a [RawPractitioner] domain model.
 */
@Component
class PractitionerSearchMapper(
    private val telecomMxidMapper: TelecomMxidMapper
) {

    /** @return the practitioner domain model with trust level, mxid and visibility derived from meta/telecom */
    fun toDomain(practitioner: Practitioner) = RawPractitioner(
        id = "Practitioner/${practitioner.idPart}",
        name = practitioner.formatName(),
        trustLevel = TrustLevel.fromMeta(practitioner.meta),
        mxid = telecomMxidMapper.extractMxid(practitioner.telecom),
        prefix = practitioner.nameFirstRep?.prefix?.joinToString(" ") { it.value }?.ifBlank { null },
        suffix = practitioner.nameFirstRep?.suffix?.joinToString(" ") { it.value }?.ifBlank { null },
        visibility = Visibility.fromMeta(practitioner.meta)
    )

    private fun Practitioner.formatName(): String = buildString {
        val name = nameFirstRep

        val prefix = name.prefix.joinToString(" ") { it.value }
        if (prefix.isNotBlank()) append("$prefix ")

        append(name.family.orEmpty())

        val given = name.given.joinToString(" ") { it.value }
        if (given.isNotBlank()) append(", $given")

        val suffix = name.suffix.joinToString(" ") { it.value }
        if (suffix.isNotBlank()) append(", $suffix")
    }.trim()
}
