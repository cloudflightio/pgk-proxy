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

import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.shared.Mxid

abstract class EntityConsolidator<BUNDLE, CONSOLIDATED> {
    abstract fun consolidate(bundle: BUNDLE, mxid: Mxid): CONSOLIDATED?

    protected companion object {
        private const val MAX_ORG_CHAIN_DEPTH = 3
    }

    fun getOrganizationChain(
        organization: RawOrganization?,
        orgIndex: Map<String, RawOrganization>,
        fetchById: (String) -> RawOrganization? = { null }
    ): OrgChain {
        val chain = mutableListOf<RawOrganization>()
        val seen = mutableSetOf<String>()
        var current = organization

        while (current != null && chain.size < MAX_ORG_CHAIN_DEPTH) {
            if (!seen.add(current.id)) break
            chain.add(current)
            current = current.partOfReference?.let { reference ->
                orgIndex[reference] ?: fetchById(reference.removePrefix("Organization/"))
            }
        }

        return OrgChain(chain)
    }
}
