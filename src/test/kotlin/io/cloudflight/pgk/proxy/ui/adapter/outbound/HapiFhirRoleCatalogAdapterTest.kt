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
import io.cloudflight.pgk.proxy.ui.adapter.outbound.mapper.RoleCatalogEntryMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import java.time.Duration

class HapiFhirRoleCatalogAdapterTest {

    private val fhirContext = FhirContext.forR4()
    private val resourcePatternResolver = mockk<ResourcePatternResolver>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private fun createAdapter(ttl: Duration = Duration.ofHours(1)) = HapiFhirRoleCatalogAdapter(
        fhirContext = fhirContext,
        properties = ProxyProperties(
            roleCatalog = ProxyProperties.RoleCatalogProperties(localFolder = "classpath:valuesets/roles/")
        ),
        resourcePatternResolver = resourcePatternResolver,
        roleCatalogEntryMapper = RoleCatalogEntryMapper(),
        applicationEvents = eventPublisher,
        ttl = ttl
    )

    private fun localResource(json: String, filename: String = "local.json"): Resource {
        val resource = mockk<Resource>()
        every { resource.isReadable } returns true
        every { resource.filename } returns filename
        every { resource.inputStream } returns json.byteInputStream()
        return resource
    }

    @Test
    fun `loads all concepts from the local valuesets with their titles`() {
        every { resourcePatternResolver.getResources(any()) } returns arrayOf(
            localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/local",
                "title": "Local Roles",
                "concept": [ { "code": "HAP", "display": "Hausarzt" }, { "code": "INT", "display": "Internist" } ]
            }""")
        )

        val result = createAdapter().loadEntries()

        assertEquals(
            listOf(
                RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles"),
                RoleCatalogEntry("https://cs/local", "INT", "Internist", title = "Local Roles")
            ),
            result
        )
    }

    @Test
    fun `merges concepts from multiple local valuesets`() {
        every { resourcePatternResolver.getResources(any()) } returns arrayOf(
            localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/fach",
                "title": "Fachaerzte",
                "concept": [ { "code": "HAP", "display": "Hausarzt" } ]
            }""", "fach.json"),
            localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/gtel",
                "title": "GTel Rollen",
                "concept": [ { "code": "OFA", "display": "Oberarzt" } ]
            }""", "gtel.json")
        )

        val result = createAdapter().loadEntries()

        assertEquals(2, result.size)
        assertTrue(result.any { it.code == "HAP" && it.title == "Fachaerzte" })
        assertTrue(result.any { it.code == "OFA" && it.title == "GTel Rollen" })
    }

    @Test
    fun `skips local valuesets that cannot be read`() {
        val readable = localResource("""{
            "resourceType": "CodeSystem",
            "url": "https://cs/local",
            "title": "Local Roles",
            "concept": [ { "code": "HAP", "display": "Hausarzt" } ]
        }""", "local.json")
        val unreadable = mockk<Resource>()
        every { unreadable.isReadable } returns false

        every { resourcePatternResolver.getResources(any()) } returns arrayOf(readable, unreadable)

        val result = createAdapter().loadEntries()

        assertEquals(
            listOf(RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles")),
            result
        )
    }

    @Test
    fun `caches entries across lookups until the ttl expires`() {
        every { resourcePatternResolver.getResources(any()) } returns arrayOf(
            localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/local",
                "title": "Local Roles",
                "concept": [ { "code": "HAP", "display": "Hausarzt" } ]
            }""")
        )

        val adapter = createAdapter()

        assertEquals(listOf(RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles")), adapter.loadEntries())
        assertEquals(listOf(RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles")), adapter.loadEntries())
        verify(exactly = 1) { resourcePatternResolver.getResources(any()) }
    }

    @Test
    fun `retries on the next lookup when the first load is empty`() {
        every { resourcePatternResolver.getResources(any()) } returnsMany listOf(
            emptyArray(),
            arrayOf(localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/local",
                "title": "Local Roles",
                "concept": [ { "code": "HAP", "display": "Hausarzt" } ]
            }"""))
        )

        val adapter = createAdapter()

        assertTrue(adapter.loadEntries().isEmpty())
        assertEquals(
            listOf(RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles")),
            adapter.loadEntries()
        )
        verify(exactly = 2) { resourcePatternResolver.getResources(any()) }
    }

    @Test
    fun `keeps the previous snapshot when a reload returns no entries`() {
        every { resourcePatternResolver.getResources(any()) } returnsMany listOf(
            arrayOf(localResource("""{
                "resourceType": "CodeSystem",
                "url": "https://cs/local",
                "title": "Local Roles",
                "concept": [ { "code": "HAP", "display": "Hausarzt" } ]
            }""")),
            emptyArray()
        )

        val adapter = createAdapter(ttl = Duration.ofSeconds(-1))

        val expected = listOf(RoleCatalogEntry("https://cs/local", "HAP", "Hausarzt", title = "Local Roles"))
        assertEquals(expected, adapter.loadEntries())
        assertEquals(expected, adapter.loadEntries())
        verify(exactly = 2) { resourcePatternResolver.getResources(any()) }
    }
}
