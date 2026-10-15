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

package io.cloudflight.pgk.proxy.ui.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import org.hl7.fhir.r4.model.CodeSystem
import org.springframework.stereotype.Component

@Component
class RoleCatalogEntryMapper {

    fun toEntries(codeSystem: CodeSystem, title: String): List<RoleCatalogEntry> =
        codeSystem.concept.flatMap { visit(it, codeSystem.url, title) }

    private fun visit(
        concept: CodeSystem.ConceptDefinitionComponent,
        system: String,
        title: String
    ): List<RoleCatalogEntry> {
        val entries = mutableListOf(
            RoleCatalogEntry(
                system = system,
                code = concept.code,
                display = concept.display ?: concept.code,
                title = title,
                retired = isRetired(concept)
            )
        )
        concept.concept.forEach { entries.addAll(visit(it, system, title)) }
        return entries
    }

    private fun isRetired(concept: CodeSystem.ConceptDefinitionComponent): Boolean {
        val status = concept.property
            .firstOrNull { it.code == "status" }
            ?.value
            ?.primitiveValue()
        return status == "retired" || status == "deprecated"
    }
}
