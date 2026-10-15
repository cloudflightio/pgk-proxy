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

import io.cloudflight.pgk.proxy.federation.port.FederationWhitelistPort
import io.cloudflight.pgk.proxy.federation.domain.FederationCheckResult
import io.cloudflight.pgk.proxy.federation.domain.FederationWhitelist
import io.cloudflight.pgk.proxy.shared.events.FederationBlockedEvent
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class FederationFilterServiceImpl(
    private val whitelist: FederationWhitelist,
    private val eventPublisher: ApplicationEventPublisher
) : FederationFilterService, FederationWhitelistPort {

    override fun isDomainAllowed(fqdn: Fqdn, direction: InvitationDirection): Boolean =
        checkDomain(fqdn, direction).allowed

    override fun checkDomain(fqdn: Fqdn, direction: InvitationDirection): FederationCheckResult {
        if (whitelist.isAllowed(fqdn)) {
            return FederationCheckResult(
                allowed = true,
                fqdn = fqdn,
                reason = "Domain is on the PGK federation whitelist"
            )
        }

        eventPublisher.publishEvent(FederationBlockedEvent(fqdn = fqdn, direction = direction))
        return FederationCheckResult(
            allowed = false,
            fqdn = fqdn,
            reason = "Domain is not on the PGK federation whitelist"
        )
    }
}
