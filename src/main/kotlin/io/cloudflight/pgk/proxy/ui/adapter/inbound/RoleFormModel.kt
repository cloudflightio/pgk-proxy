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

package io.cloudflight.pgk.proxy.ui.adapter.inbound

import io.cloudflight.pgk.proxy.search.api.domain.model.ExistingRoleRowOption
import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleChipOption
import io.cloudflight.pgk.proxy.ui.application.RoleManagementService
import org.springframework.ui.Model

internal fun Model.addEmptyRoleForm() {
    addAttribute("practitionerId", "")
    addAttribute("practitionerName", "")
    addAttribute("practitionerLocked", false)
    addAttribute("organizationId", "")
    addAttribute("organizationName", "")
    addAttribute("rootOrganizationId", "")
    addAttribute("selectedRoles", emptyList<RoleChipOption>())
    addAttribute("existingRoles", emptyList<ExistingRoleRowOption>())
    addAttribute("removedRoleIds", emptyList<String>())
}

/**
 * Pre-fills the role form with the logged-in user's own practitioner, its organization and its
 * existing roles. The practitioner is locked so the user can only edit their own roles.
 *
 * Falls back to the empty form when the user has no practitioner in the directory.
 */
internal fun Model.addCurrentUserRoleForm(roleManagement: RoleManagementService, userId: String) {
    val practitioner = runCatching { roleManagement.findPractitionerByUserId(userId) }.getOrNull()
    if (practitioner == null) {
        addEmptyRoleForm()
        return
    }

    val organization = if (practitioner.organizationId.isNotBlank()) {
        OrganizationOption(practitioner.organizationId, practitioner.organizationName)
    } else {
        runCatching { roleManagement.findPrimaryOrganization(practitioner.id) }.getOrNull()
    }
    val organizationId = organization?.id.orEmpty()

    addAttribute("practitionerId", practitioner.id)
    addAttribute("practitionerName", practitioner.name)
    addAttribute("practitionerLocked", true)
    addAttribute("organizationId", organizationId)
    addAttribute("organizationName", organization?.name.orEmpty())
    addAttribute("rootOrganizationId", organizationId)
    addAttribute("selectedRoles", emptyList<RoleChipOption>())
    addAttribute(
        "existingRoles",
        runCatching { roleManagement.findExistingRoles(practitioner.id, emptyList(), organizationId) }
            .getOrDefault(emptyList())
    )
    addAttribute("removedRoleIds", emptyList<String>())
}
