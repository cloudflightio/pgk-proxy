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

import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.DisplayNameQueryPort
import io.cloudflight.pgk.proxy.search.port.FindOrganizationByIdPort
import io.cloudflight.pgk.proxy.search.port.FindProvisionedPractitionersPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Service

@Service
class DisplayNameQueryService(
    private val findPractitioners: FindProvisionedPractitionersPort,
    private val findOrganizationById: FindOrganizationByIdPort,
    private val practitionerConsolidator: PractitionerConsolidator,
    private val auditLogger: AuditLogger
) : DisplayNameQueryPort {

    override fun queryDisplayNames(): Map<Mxid, String> {
        val bundle = findPractitioners.findProvisioned()
        val organizationsById = bundle.organizations.associateBy { it.id }

        auditLogger.debug(
            "system", AuditAction.DisplayNameCompute,
            "Fetched practitioner bundle for display names: ${bundle.practitioners.size} practitioners, " +
                "${bundle.practitionerRoles.size} roles, ${bundle.organizations.size} organizations"
        )
        auditLogger.debug("system", AuditAction.DisplayNameCompute, "Bundle: $bundle")

        return bundle.practitioners
            .mapNotNull { practitioner ->
                val mxid = practitioner.mxid ?: return@mapNotNull null
                val roleZip = bundle.practitionerRoles
                    .filter { it.practitionerReference == practitioner.id }
                    .map { role ->
                        val organization = role.organizationReference?.let { organizationsById[it] }
                        role to practitionerConsolidator.getOrganizationChain(
                            organization, organizationsById, findOrganizationById::findById
                        )
                    }
                val displayName = practitionerConsolidator.buildDisplayName(practitioner, roleZip, includeRoles = false)
                auditLogger.debug(
                    "system", AuditAction.DisplayNameCompute,
                    "Computed display name for mxid=${mxid.value}: '$displayName'"
                )
                mxid to displayName
            }
            .toMap()
            .also { auditLogger.info("system", AuditAction.DisplayNameCompute, "Computed ${it.size} chat display names from the Provider Directory") }
    }
}
