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
import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Component

/**
 * Turns a fetched practitioner bundle into a [ConsolidatedUser] for the given mxid.
 */
@Component
class PractitionerConsolidator(
    private val findOrganizationById: FindOrganizationByIdPort,
    private val auditLogger: AuditLogger
) : EntityConsolidator<PractitionerSearchBundle, ConsolidatedUser>() {

    /**
     * Consolidates the practitioner matching [mxid] with its roles and organization chains.
     *
     * @return the consolidated user, or null if the bundle contains no practitioner for [mxid]
     */
    override fun consolidate(
        bundle: PractitionerSearchBundle, mxid: Mxid
    ): ConsolidatedUser? {
        val indexedOrgs = bundle.organizations.associateBy { it.id }
        val practitioners = bundle.practitioners.filter { it.mxid == mxid }
        val practitionerIds = practitioners.map { it.id }.toSet()
        val practitionerRoles = bundle.practitionerRoles.filter { it.practitionerReference in practitionerIds }
        val organizationChains =
            practitionerRoles.map {
                getOrganizationChain(
                    indexedOrgs[it.organizationReference],
                    indexedOrgs,
                    findOrganizationById::findById
                )
            }

        if (practitioners.isEmpty()) {
            auditLogger.warn(
                mxid.value, AuditAction.SearchConsolidatePractitioner,
                "Found no practitioners in fetched bundle for mxid $mxid"
            )
            return null
        }

        return ConsolidatedUser(
            mxid = mxid,
            displayName = buildDisplayName(
                practitioner = practitioners.first(),
                roleZip = practitionerRoles.zip(organizationChains)
            ),
            roles = practitionerRoles,
            organizationChains = organizationChains,
            visibility = practitioners.first().visibility
        )
    }

    /**
     * Builds a display name from the practitioner name plus its distinct role and org-chain names.
     */
    fun buildDisplayName(
        practitioner: RawPractitioner,
        roleZip: List<Pair<RawPractitionerRole, OrgChain>>,
        includeRoles: Boolean = true,
    ): String {
        val roleNames = if (includeRoles) {
            roleZip.mapNotNull { (role, _) -> role.codeDisplay }.distinct().joinToString(", ")
        } else {
            ""
        }
        val chainNames = roleZip
            .map { (_, chain) -> chain }
            .distinctBy { it.root()?.id }
            .mapNotNull { it.display().takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
        val suffix = listOf(roleNames, chainNames).filter { it.isNotBlank() }.joinToString(", ")

        val displayName = buildString {
            append(practitioner.name)
            if (suffix.isNotBlank()) append(" ($suffix)")
        }

        auditLogger.debug(
            "system", AuditAction.DisplayNameCompute,
            "Built display name for practitioner id=${practitioner.id} name='${practitioner.name}': " +
                "roleNames='$roleNames' chainNames='$chainNames' suffix='$suffix' -> '$displayName'"
        )
        return displayName
    }
}
