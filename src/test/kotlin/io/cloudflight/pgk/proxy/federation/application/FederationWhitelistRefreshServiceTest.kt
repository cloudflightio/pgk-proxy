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

package io.cloudflight.pgk.proxy.federation.application

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.federation.domain.FederationEndpoint
import io.cloudflight.pgk.proxy.federation.domain.FederationWhitelist
import io.cloudflight.pgk.proxy.federation.port.FederationListPort
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.events.FederationListRefreshFailedEvent
import io.cloudflight.pgk.proxy.shared.events.WhitelistFetchedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration

class FederationWhitelistRefreshServiceTest {

    private val listPort = mockk<FederationListPort>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val whitelist = FederationWhitelist()
    private val properties =
        ProxyProperties(federation = ProxyProperties.FederationProperties(refreshIntervalMinutes = 60))

    private val refreshService = FederationWhitelistRefreshService(
        listPort = listPort,
        whitelist = whitelist,
        eventPublisher = eventPublisher,
        properties = properties,
        auditLogger = AuditLogger()
    )

    @Test
    @DisplayName("registers a fixed delay task using the configured refresh interval in minutes")
    fun testRegistersFixedDelayTaskWithConfiguredRefreshInterval() {
        val registrar = mockk<ScheduledTaskRegistrar>(relaxed = true)

        refreshService.configureTasks(registrar)

        verify {
            registrar.addFixedDelayTask(any<Runnable>(), Duration.ofMinutes(60))
        }
    }

    @Test
    @DisplayName("replaces the whitelist with the fetched endpoints")
    fun testReplacesWhitelistWithFetchedEndpoints() {
        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4),
            FederationEndpoint(Fqdn("matrix.partner.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()

        assertTrue(whitelist.isAllowed(Fqdn("matrix.pgk.example")))
        assertTrue(whitelist.isAllowed(Fqdn("matrix.partner.example")))
        assertFalse(whitelist.isAllowed(Fqdn("evil.example.org")))
    }

    @Test
    @DisplayName("publishes a WhitelistFetchedEvent with the fetched FQDNs")
    fun testPublishesWhitelistFetchedEventOnSuccessfulRefresh() {
        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4),
            FederationEndpoint(Fqdn("matrix.partner.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()

        verify {
            eventPublisher.publishEvent(
                match<WhitelistFetchedEvent> {
                    it.fqdns == setOf(Fqdn("matrix.pgk.example"), Fqdn("matrix.partner.example"))
                }
            )
        }
    }

    @Test
    @DisplayName("keeps the current whitelist and publishes an event when the fetch fails")
    fun testKeepsWhitelistAndPublishesEventOnFetchFailure() {
        every { listPort.fetchFederationEndpoints() } throws RuntimeException("FHIR server unreachable")
        whitelist.replace(setOf(Fqdn("known.example.org")))

        refreshService.refresh()

        assertTrue(whitelist.isAllowed(Fqdn("known.example.org")))
        verify {
            eventPublisher.publishEvent(
                match<FederationListRefreshFailedEvent> { it.cause is RuntimeException }
            )
        }
    }

    @Test
    @DisplayName("leaves the whitelist empty when the FHIR server returns no endpoints")
    fun testLeavesWhitelistEmptyWhenNoEndpointsReturned() {
        every { listPort.fetchFederationEndpoints() } returns emptyList()
        refreshService.refresh()

        assertFalse(whitelist.isAllowed(Fqdn("matrix.pgk.example")))
        assertEquals(0, whitelist.size())
    }

    @Test
    @DisplayName("deduplicates duplicate endpoints returned by FHIR")
    fun testDeduplicatesDuplicateEndpoints() {
        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4),
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4),
            FederationEndpoint(Fqdn("matrix.partner.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()

        assertTrue(whitelist.isAllowed(Fqdn("matrix.pgk.example")))
        assertTrue(whitelist.isAllowed(Fqdn("matrix.partner.example")))
        assertFalse(whitelist.isAllowed(Fqdn("unrelated.example.org")))
    }

    @Test
    @DisplayName("a second refresh overrides stale endpoints")
    fun testSecondRefreshOverridesStaleEndpoints() {
        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()
        assertTrue(whitelist.isAllowed(Fqdn("matrix.pgk.example")))

        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.partner.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()

        assertFalse(whitelist.isAllowed(Fqdn("matrix.pgk.example")))
        assertTrue(whitelist.isAllowed(Fqdn("matrix.partner.example")))
    }

    @Test
    @DisplayName("a failed refresh keeps previously fetched FHIR servers")
    fun testFailedRefreshKeepsPreviouslyFetchedFhirServers() {
        every { listPort.fetchFederationEndpoints() } returns listOf(
            FederationEndpoint(Fqdn("matrix.pgk.example"), TrustLevel.TL_4)
        )
        refreshService.refresh()
        assertTrue(whitelist.isAllowed(Fqdn("matrix.pgk.example")))

        every { listPort.fetchFederationEndpoints() } throws RuntimeException("FHIR server unreachable")
        refreshService.refresh()

        assertTrue(whitelist.isAllowed(Fqdn("matrix.pgk.example")))
    }
}
