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

package io.cloudflight.pgk.proxy.ui.adapter.outbound

import ca.uhn.fhir.context.FhirContext
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.port.RoleCatalogPort
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetLoadedEvent
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetParseFailedEvent
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetSkippedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogEmptyEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogReloadedEvent
import io.cloudflight.pgk.proxy.shared.events.ValuesetFolderScanFailedEvent
import io.cloudflight.pgk.proxy.ui.adapter.outbound.mapper.RoleCatalogEntryMapper
import org.hl7.fhir.r4.model.CodeSystem
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Component
class HapiFhirRoleCatalogAdapter(
    private val fhirContext: FhirContext,
    private val properties: ProxyProperties,
    private val resourcePatternResolver: ResourcePatternResolver,
    private val roleCatalogEntryMapper: RoleCatalogEntryMapper,
    private val applicationEvents: ApplicationEventPublisher,
    private val ttl: Duration = Duration.ofHours(1)
) : RoleCatalogPort {

    private val cache = AtomicReference<Snapshot?>(null)

    private data class Snapshot(val entries: List<RoleCatalogEntry>, val loadedAt: Instant)

    override fun loadEntries(): List<RoleCatalogEntry> = snapshot().entries

    private fun snapshot(): Snapshot {
        cache.get()?.let { current ->
            if (!current.isExpired()) return current
        }

        return load()
    }

    private fun load(): Snapshot {
        val entries = runCatching { loadFromValuesets() }
            .onFailure { applicationEvents.publishEvent(RoleCatalogLoadFailedEvent(cause = it)) }
            .getOrDefault(emptyList())

        cache.get()?.let { current ->
            if (current.entries.isNotEmpty() && entries.isEmpty()) {
                applicationEvents.publishEvent(RoleCatalogEmptyEvent(keptPreviousSnapshot = true))
                return current
            }
        }
        if (entries.isEmpty()) {
            applicationEvents.publishEvent(RoleCatalogEmptyEvent(keptPreviousSnapshot = false))
            return Snapshot(emptyList(), Instant.now())
        }
        return Snapshot(entries, Instant.now()).also { cache.set(it) }
    }

    private fun Snapshot.isExpired(): Boolean = Duration.between(loadedAt, Instant.now()) > ttl

    private fun loadFromValuesets(): List<RoleCatalogEntry> {
        val entries = loadLocalValuesets().flatMap { codeSystem ->
            roleCatalogEntryMapper.toEntries(codeSystem, codeSystem.title ?: codeSystem.url)
        }

        applicationEvents.publishEvent(RoleCatalogReloadedEvent(entries = entries.size))
        return entries
    }

    private fun loadLocalValuesets(): List<CodeSystem> =
        resourcesIn(properties.roleCatalog.localFolder)
            .filter { it.isReadable }
            .mapNotNull { resource ->
                val codeSystem = readResource(resource)
                if (codeSystem == null) {
                    applicationEvents.publishEvent(LocalValuesetSkippedEvent(filename = resource.filename.orEmpty()))
                    null
                } else {
                    applicationEvents.publishEvent(
                        LocalValuesetLoadedEvent(title = codeSystem.title, filename = resource.filename.orEmpty())
                    )
                    codeSystem
                }
            }

    private fun resourcesIn(folder: String): Array<Resource> {
        val pattern = folder.let { if (it.endsWith("/")) it else "$it/" } + "**/*.json"
        return runCatching { resourcePatternResolver.getResources(pattern) }
            .getOrElse { error ->
                applicationEvents.publishEvent(ValuesetFolderScanFailedEvent(folder = folder, cause = error))
                emptyArray()
            }
    }

    private fun readResource(resource: Resource): CodeSystem? = runCatching {
        val json = resource.inputStream.bufferedReader().use { it.readText() }
        fhirContext.newJsonParser().parseResource(json) as? CodeSystem
    }.onFailure { applicationEvents.publishEvent(LocalValuesetParseFailedEvent(filename = resource.filename.orEmpty(), cause = it)) }
        .getOrNull()
}
