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

package io.cloudflight.pgk.proxy.search.domain.model

import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.Visibility

/**
 * Fully resolved user (mxid, display name, roles, orgs, visibility) used for search filtering.
 */
class ConsolidatedUser(
    val mxid: Mxid,
    val displayName: String,
    val roles: List<RawPractitionerRole> = emptyList(),
    val organizationChains: List<OrgChain> = emptyList(),
    val visibility: Visibility,
) {

    /** All distinct organizations across all chains. */
    val organizations: List<RawOrganization>
        get() = organizationChains.flatMap { it.organizationChain }.distinctBy { it.id }

    /**
     * The root of the first organization chain. Under the current assumption all
     * chains belonging to a user resolve to a single root organization.
     */
    val rootOrganization: RawOrganization?
        get() = organizationChains.firstNotNullOfOrNull { it.root() }

    /** Distinct display names of all roles. */
    val roleNames: List<String>
        get() = roles.mapNotNull { it.codeDisplay }.distinct()

    /** Whether any role carries the given code. */
    fun hasRole(code: String): Boolean =
        roles.any { role -> role.codes.any { it.code == code } }

    /** @return the user-directory DTO representation of this user */
    fun toEntry(): UserDirectoryEntry =
        UserDirectoryEntry(userId = mxid.value, displayName = displayName)

    override fun toString(): String =
        "ConsolidatedUser(" +
                "mxid=$mxid, " +
                "displayName='$displayName', " +
                "roles=$roles, " +
                "organizationChains=$organizationChains, " +
                "visibility=$visibility" +
                ")"
}
