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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.cloudflight.pgk.proxy.provisioning.port.OrganizationTypeCatalogPort
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetParseFailedEvent
import io.cloudflight.pgk.proxy.shared.events.ValuesetFolderScanFailedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Component


@Component
class LocalOrganizationTypeCatalogAdapter(
    private val resourcePatternResolver: ResourcePatternResolver,
    private val applicationEvents: ApplicationEventPublisher
) : OrganizationTypeCatalogPort {

    private val objectMapper = ObjectMapper()

    override fun loadTypes(): List<OrganizationType> =
        resourcesIn("classpath:valuesets/organizations/")
            .filter { it.isReadable }
            .flatMap { resource -> readTypes(resource) }

    private fun resourcesIn(folder: String): Array<Resource> {
        val pattern = folder.let { if (it.endsWith("/")) it else "$it/" } + "*.json"
        return runCatching { resourcePatternResolver.getResources(pattern) }
            .getOrElse { error ->
                applicationEvents.publishEvent(ValuesetFolderScanFailedEvent(folder = folder, cause = error))
                emptyArray()
            }
    }

    private fun readTypes(resource: Resource): List<OrganizationType> =
        runCatching {
            val json = resource.inputStream.bufferedReader().use { it.readText() }
            val concepts = objectMapper.readTree(json)["concept"]
            if (concepts !is ArrayNode) {
                emptyList()
            } else {
                concepts.filter { it.isObject }
                    .mapNotNull { concept -> toType(concept) }
            }
        }.onFailure { error ->
            applicationEvents.publishEvent(
                LocalValuesetParseFailedEvent(filename = resource.filename.orEmpty(), cause = error)
            )
        }.getOrElse { emptyList() }

    private fun toType(concept: JsonNode): OrganizationType? {
        val code = concept.path("code").asText("")
        if (code.isBlank() || isGroupHeader(concept)) return null
        return OrganizationType(code = code, display = concept.path("display").asText(code))
    }

    private fun isGroupHeader(concept: JsonNode): Boolean {
        val property = concept["property"]
        return property is ArrayNode && property.any { it.path("code").asText() == "child" }
    }
}
