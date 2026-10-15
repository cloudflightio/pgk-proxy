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
import io.cloudflight.pgk.proxy.federation.domain.FederationWhitelist
import io.cloudflight.pgk.proxy.federation.port.FederationListPort
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.events.FederationListRefreshFailedEvent
import io.cloudflight.pgk.proxy.shared.events.WhitelistFetchedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Periodically refreshes the federation whitelist by fetching the known federation endpoints from
 * the local FHIR server. On success the in-memory [FederationWhitelist] is replaced with the fetched
 * endpoints and a [WhitelistFetchedEvent] is published so downstream consumers (e.g. the egress
 * proxy writer) can react. On failure the current whitelist is kept and a [FederationListRefreshFailedEvent]
 * is published.
 */
@Service
class FederationWhitelistRefreshService(
    private val listPort: FederationListPort,
    private val whitelist: FederationWhitelist,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: ProxyProperties,
    private val auditLogger: AuditLogger
) : FederationListRefreshService, SchedulingConfigurer {

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        val interval = Duration.ofMinutes(properties.federation.refreshIntervalMinutes)
        taskRegistrar.addFixedDelayTask(
            { refresh() },
            interval
        )
    }

    override fun refresh() {
        val previousSize = whitelist.size()
        val endpoints = try {
            listPort.fetchFederationEndpoints()
        } catch (e: Exception) {
            eventPublisher.publishEvent(FederationListRefreshFailedEvent(cause = e))
            return
        }

        val fqdns = endpoints.map { it.fqdn }.toSet()
        whitelist.replace(fqdns)

        eventPublisher.publishEvent(WhitelistFetchedEvent(fqdns))

        auditLogger.info(
            "system", AuditAction.FederationWhitelistRefresh,
            "Federation whitelist refreshed: ${endpoints.size} endpoints → ${whitelist.size()} allowed servers (was $previousSize)"
        )
    }
}
