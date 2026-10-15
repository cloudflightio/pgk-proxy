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

package io.cloudflight.pgk.keycloakprovider

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DisplayNameProviderTest {

    private val proxyClient = mockk<ProxyClient>()

    private fun provider(refreshMinutes: Long) = DisplayNameProvider(proxyClient, "matrix.traefik.me", refreshMinutes)

    private val names = mapOf("@max:matrix.traefik.me" to "Mustermann, Max (AKH Wien)")

    @Test
    @DisplayName("Resolves the display name for a localpart using the configured matrix domain")
    fun resolvesDisplayName() {
        every { proxyClient.fetchDisplayNames() } returns names

        val provider = provider(60)

        assertEquals("Mustermann, Max (AKH Wien)", provider.displayNameFor("max"))
        verify(exactly = 1) { proxyClient.fetchDisplayNames() }
    }

    @Test
    @DisplayName("Caches the fetched names for the refresh interval")
    fun cachesWithinRefreshInterval() {
        every { proxyClient.fetchDisplayNames() } returns names

        val provider = provider(60)

        provider.displayNameFor("max")
        provider.displayNameFor("max")
        provider.displayNameFor("unknown")

        verify(exactly = 1) { proxyClient.fetchDisplayNames() }
    }

    @Test
    @DisplayName("A localpart without an entry yields null")
    fun unknownLocalpartYieldsNull() {
        every { proxyClient.fetchDisplayNames() } returns names

        val provider = provider(60)

        assertNull(provider.displayNameFor("nobody"))
    }

    @Test
    @DisplayName("A refresh interval of zero disables caching")
    fun zeroRefreshIntervalDisablesCaching() {
        every { proxyClient.fetchDisplayNames() } returns names

        val provider = provider(0)

        provider.displayNameFor("max")
        provider.displayNameFor("max")

        verify(exactly = 2) { proxyClient.fetchDisplayNames() }
    }

    @Test
    @DisplayName("An empty result is not cached and is retried on the next call")
    fun emptyResultIsRetried() {
        every { proxyClient.fetchDisplayNames() } returns emptyMap()

        val provider = provider(60)

        assertNull(provider.displayNameFor("max"))
        assertNull(provider.displayNameFor("max"))

        verify(exactly = 2) { proxyClient.fetchDisplayNames() }
    }
}
