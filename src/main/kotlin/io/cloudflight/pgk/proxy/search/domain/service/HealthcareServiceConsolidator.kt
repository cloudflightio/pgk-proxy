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

package io.cloudflight.pgk.proxy.search.domain.service

import io.cloudflight.pgk.proxy.search.domain.model.ConsolidatedUser
import io.cloudflight.pgk.proxy.search.domain.model.HealthcareServiceSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawHealthcareService
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Component

/**
 * Turns a fetched healthcare-service bundle into a [ConsolidatedUser] for the given mxid.
 */
@Component
class HealthcareServiceConsolidator(
    private val auditLogger: AuditLogger
) : EntityConsolidator<HealthcareServiceSearchBundle, ConsolidatedUser>() {

    /**
     * Consolidates the healthcare service matching [mxid] with its organization chain.
     *
     * @return the consolidated user, or null if the bundle contains no service for [mxid]
     */
    override fun consolidate(
        bundle: HealthcareServiceSearchBundle, mxid: Mxid
    ): ConsolidatedUser? {
        val indexedOrgs = bundle.organizations.associateBy { it.id }
        val healthcareServices = bundle.healthcareServices.filter { it.mxid == mxid }
        val orgChains =
            healthcareServices.map { getOrganizationChain(indexedOrgs[it.organizationReference], indexedOrgs) }

        if (healthcareServices.isEmpty()) {
            auditLogger.warn(
                mxid.value, AuditAction.SearchConsolidateHealthcareService,
                "Found no healthcareServices for $mxid"
            )
            return null
        }

        return ConsolidatedUser(
            mxid = mxid,
            displayName = buildDisplayName(
                healthcareService = healthcareServices.first(),
                orgChain = orgChains.first()
            ),
            organizationChains = orgChains,
            visibility = healthcareServices.first().visibility
        )
    }

    private fun buildDisplayName(
        healthcareService: RawHealthcareService,
        orgChain: OrgChain,
    ): String {
        return buildString {
            append(healthcareService.name)
            append(", ")
            append(orgChain.display())
        }
    }
}
