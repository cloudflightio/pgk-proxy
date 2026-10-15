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

package io.cloudflight.pgk.proxy.federation.domain

import io.cloudflight.pgk.proxy.shared.Fqdn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FederationWhitelistTest {

    private val whitelist = FederationWhitelist()

    private val serverA = Fqdn("matrix.pgk.example")
    private val serverB = Fqdn("matrix.partner.example")

    @Test
    @DisplayName("starts empty and allows nothing")
    fun testStartsEmptyAndAllowsNothing() {
        assertFalse(whitelist.isAllowed(serverA))
        assertEquals(0, whitelist.size())
    }

    @Test
    @DisplayName("allows a domain after it is replaced into the whitelist")
    fun testAllowsDomainAfterReplace() {
        whitelist.replace(setOf(serverA))

        assertTrue(whitelist.isAllowed(serverA))
        assertFalse(whitelist.isAllowed(serverB))
    }

    @Test
    @DisplayName("replace overrides the previous whitelist")
    fun testReplaceOverridesPreviousWhitelist() {
        whitelist.replace(setOf(serverA))
        whitelist.replace(setOf(serverB))

        assertFalse(whitelist.isAllowed(serverA))
        assertTrue(whitelist.isAllowed(serverB))
    }

    @Test
    @DisplayName("tracks the number of allowed servers")
    fun testTracksNumberOfAllowedServers() {
        whitelist.replace(setOf(serverA, serverB))

        assertEquals(2, whitelist.size())
    }

    @Test
    @DisplayName("allows all domains of the whitelist")
    fun testAllowsAllDomainsOfTheWhitelist() {
        whitelist.replace(setOf(serverA, serverB))

        assertTrue(whitelist.isAllowed(serverA))
        assertTrue(whitelist.isAllowed(serverB))
    }

    @Test
    @DisplayName("is empty after replacing with an empty set")
    fun testIsEmptyAfterReplacingWithEmptySet() {
        whitelist.replace(setOf(serverA))
        whitelist.replace(emptySet())

        assertFalse(whitelist.isAllowed(serverA))
        assertEquals(0, whitelist.size())
    }
}
