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

package io.cloudflight.pgk.proxy.ui.application

import io.cloudflight.pgk.proxy.search.api.domain.model.VisibilityEntry
import io.cloudflight.pgk.proxy.search.api.port.HealthcareServiceVisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityUpdatePort
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.VisibilityLoadFailedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Application service for the visibility admin page (Sichtbarkeit).
 *
 * Rows come from the FHIR provider directory via the search module's [VisibilityQueryPort] and
 * [HealthcareServiceVisibilityQueryPort]: practitioners and healthcare services, each only
 * when they carry a Matrix mxid. Changing visibility delegates to
 * [VisibilityUpdatePort], which persists the value as a FHIR meta security tag.
 */
@Service
class VisibilityService(
    private val visibilityQuery: VisibilityQueryPort,
    private val healthcareServiceVisibilityQuery: HealthcareServiceVisibilityQueryPort,
    private val visibilityUpdate: VisibilityUpdatePort,
    private val eventPublisher: ApplicationEventPublisher
) {

    fun listPractitioners(): List<VisibilityRow> =
        runCatching { visibilityQuery.listProvisionedPractitioners() }
            .onFailure { eventPublisher.publishEvent(VisibilityLoadFailedEvent("practitioners", it)) }
            .getOrDefault(emptyList())
            .map { it.toRow() }

    fun listHealthcareProviders(): List<VisibilityRow> =
        runCatching { healthcareServiceVisibilityQuery.listProvisionedHealthcareServices() }
            .onFailure { eventPublisher.publishEvent(VisibilityLoadFailedEvent("healthcare services", it)) }
            .getOrDefault(emptyList())
            .map { it.toRow() }

    fun setVisibility(id: String, visibility: Visibility) {
        visibilityUpdate.setVisibility(id, visibility)
    }

    private fun VisibilityEntry.toRow() = VisibilityRow(
        id = id,
        name = name,
        subline = mxid?.value.orEmpty(),
        initials = initialsOf(name),
        visibility = visibility
    )

    private fun initialsOf(name: String): String =
        name.replace(TITLE_PREFIX, "")
            .split(" ", ",")
            .filter { it.isNotBlank() }
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .uppercase()
            .take(2)

    companion object {
        /** Common academic title prefixes (Dr., Prof., Mag., ...) that must not end up in the avatar. */
        private val TITLE_PREFIX = Regex(
            "\\b(dr\\.?|prof\\.?|mag\\.?|dipl\\.[- ]?ing\\.?|univ\\.-?prof\\.?|univ\\.-?doz\\.?|privat\\.-?doz\\.?|prim\\.?|oa\\.?|ba\\.?|bsc|msc)\\s+",
            RegexOption.IGNORE_CASE
        )
    }
}
