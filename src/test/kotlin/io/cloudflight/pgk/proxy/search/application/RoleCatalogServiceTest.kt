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

package io.cloudflight.pgk.proxy.search.application

import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.port.RoleCatalogPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RoleCatalogServiceTest {

    private val roleCatalogPort = mock<RoleCatalogPort>()
    private val service = RoleCatalogService(roleCatalogPort)

    @Test
    fun `search delegates to the port on every call`() {
        val entry = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt")
        whenever(roleCatalogPort.loadEntries()).thenReturn(listOf(entry))

        assertEquals(listOf(entry), service.search("hausarzt"))
        assertEquals(listOf(entry), service.search("hausarzt"))
        verify(roleCatalogPort, times(2)).loadEntries()
    }

    @Test
    fun `findByToken resolves a full token against the port entries`() {
        val entry = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt")
        whenever(roleCatalogPort.loadEntries()).thenReturn(listOf(entry))

        assertEquals(entry, service.findByToken("https://example/cs|HAP"))
        verify(roleCatalogPort, times(1)).loadEntries()
    }

    @Test
    fun `findByToken falls back to the bare code`() {
        val entry = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt")
        whenever(roleCatalogPort.loadEntries()).thenReturn(listOf(entry))

        assertEquals(entry, service.findByToken("HAP"))
    }

    @Test
    fun `findByToken returns null for an unknown token`() {
        whenever(roleCatalogPort.loadEntries()).thenReturn(emptyList())

        assertNull(service.findByToken("https://example/cs|HAP"))
    }

    @Test
    fun `filters by display and skips retired roles`() {
        val active = RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt")
        val retired = RoleCatalogEntry("https://example/cs", "OLD", "Alter Hausarzt", retired = true)
        whenever(roleCatalogPort.loadEntries()).thenReturn(listOf(active, retired))

        assertEquals(listOf(active), service.search("arzt"))
    }
}
