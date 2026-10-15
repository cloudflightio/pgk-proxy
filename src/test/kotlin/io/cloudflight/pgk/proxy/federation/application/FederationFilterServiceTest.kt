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

import io.cloudflight.pgk.proxy.federation.domain.FederationWhitelist
import io.cloudflight.pgk.proxy.shared.events.FederationBlockedEvent
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class FederationFilterServiceTest {

    private val whitelist = FederationWhitelist()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = FederationFilterServiceImpl(whitelist, eventPublisher)

    private val allowedFqdn = Fqdn("matrix.pgk.example")
    private val blockedFqdn = Fqdn("evil.example.org")

    @Test
    @DisplayName("blocks all domains before the whitelist is populated")
    fun testBlocksAllDomainsBeforeWhitelistIsPopulated() {
        val result = service.checkDomain(allowedFqdn, InvitationDirection.INBOUND)

        assertFalse(result.allowed)
    }

    @Test
    @DisplayName("allows a whitelisted domain without publishing a block event")
    fun testAllowsWhitelistedDomainWithoutPublishingBlockEvent() {
        whitelist.replace(setOf(allowedFqdn))

        val result = service.checkDomain(allowedFqdn, InvitationDirection.INBOUND)

        assertTrue(result.allowed)
        verify(exactly = 0) { eventPublisher.publishEvent(any<FederationBlockedEvent>()) }
    }

    @Test
    @DisplayName("allows every whitelisted domain")
    fun testAllowsEveryWhitelistedDomain() {
        whitelist.replace(setOf(allowedFqdn, Fqdn("matrix.partner.example")))

        assertTrue(service.checkDomain(allowedFqdn, InvitationDirection.INBOUND).allowed)
        assertTrue(service.checkDomain(Fqdn("matrix.partner.example"), InvitationDirection.INBOUND).allowed)
    }

    @Test
    @DisplayName("allows a whitelisted domain in both directions")
    fun testAllowsWhitelistedDomainInBothDirections() {
        whitelist.replace(setOf(allowedFqdn))

        assertTrue(service.checkDomain(allowedFqdn, InvitationDirection.INBOUND).allowed)
        assertTrue(service.checkDomain(allowedFqdn, InvitationDirection.OUTBOUND).allowed)
    }

    @Test
    @DisplayName("reports a non-blank reason for a whitelisted domain")
    fun testReportsReasonForAllowedDomain() {
        whitelist.replace(setOf(allowedFqdn))

        val result = service.checkDomain(allowedFqdn, InvitationDirection.INBOUND)

        assertTrue(result.allowed)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    @DisplayName("blocks a domain not on the whitelist and publishes a block event")
    fun testBlocksDomainNotOnWhitelistAndPublishesBlockEvent() {
        whitelist.replace(setOf(allowedFqdn))

        val result = service.checkDomain(blockedFqdn, InvitationDirection.OUTBOUND)

        assertFalse(result.allowed)
        verify {
            eventPublisher.publishEvent(
                match<FederationBlockedEvent> {
                    it.fqdn == blockedFqdn && it.direction.equals(InvitationDirection.OUTBOUND)
                }
            )
        }
    }

    @Test
    @DisplayName("reports a non-blank reason for a domain not on the whitelist")
    fun testReportsReasonForBlockedDomain() {
        whitelist.replace(setOf(allowedFqdn))

        val result = service.checkDomain(blockedFqdn, InvitationDirection.INBOUND)

        assertFalse(result.allowed)
        assertTrue(result.reason.isNotBlank())
    }

    @Test
    @DisplayName("publishes a block event with the inbound direction")
    fun testPublishesBlockEventWithInboundDirection() {
        whitelist.replace(setOf(allowedFqdn))

        service.checkDomain(blockedFqdn, InvitationDirection.INBOUND)

        verify {
            eventPublisher.publishEvent(
                match<FederationBlockedEvent> { it.direction.equals(InvitationDirection.INBOUND) && it.fqdn == blockedFqdn }
            )
        }
    }

    @Test
    @DisplayName("publishes a block event with the outbound direction")
    fun testPublishesBlockEventWithOutboundDirection() {
        whitelist.replace(setOf(allowedFqdn))

        service.checkDomain(blockedFqdn, InvitationDirection.OUTBOUND)

        verify {
            eventPublisher.publishEvent(
                match<FederationBlockedEvent> { it.direction.equals(InvitationDirection.OUTBOUND) && it.fqdn == blockedFqdn }
            )
        }
    }

    @Test
    @DisplayName("publishes one block event per blocked attempt")
    fun testPublishesOneBlockEventPerAttempt() {
        whitelist.replace(setOf(allowedFqdn))

        service.checkDomain(blockedFqdn, InvitationDirection.INBOUND)
        service.checkDomain(blockedFqdn, InvitationDirection.INBOUND)

        verify(exactly = 2) { eventPublisher.publishEvent(any<FederationBlockedEvent>()) }
    }

    @Test
    @DisplayName("blocks any other domain than the whitelisted ones")
    fun testBlocksAnyDomainOtherThanWhitelisted() {
        whitelist.replace(setOf(allowedFqdn))

        assertFalse(service.checkDomain(blockedFqdn, InvitationDirection.INBOUND).allowed)
        assertFalse(service.checkDomain(Fqdn("other.example.org"), InvitationDirection.OUTBOUND).allowed)
    }

    @Test
    @DisplayName("blocks after the whitelist is replaced with an empty set")
    fun testBlocksAfterWhitelistBecomesEmpty() {
        whitelist.replace(setOf(allowedFqdn))
        whitelist.replace(emptySet())

        assertFalse(service.checkDomain(allowedFqdn, InvitationDirection.INBOUND).allowed)
    }

    @Test
    @DisplayName("isDomainAllowed returns true for a whitelisted domain")
    fun testIsDomainAllowedTrueForWhitelisted() {
        whitelist.replace(setOf(allowedFqdn))

        assertTrue(service.isDomainAllowed(allowedFqdn, InvitationDirection.INBOUND))
    }

    @Test
    @DisplayName("isDomainAllowed returns false for a domain not on the whitelist and publishes a block event")
    fun testIsDomainAllowedFalseForBlocked() {
        whitelist.replace(setOf(allowedFqdn))

        assertFalse(service.isDomainAllowed(blockedFqdn, InvitationDirection.INBOUND))
        verify { eventPublisher.publishEvent(match<FederationBlockedEvent> { it.fqdn == blockedFqdn }) }
    }
}
