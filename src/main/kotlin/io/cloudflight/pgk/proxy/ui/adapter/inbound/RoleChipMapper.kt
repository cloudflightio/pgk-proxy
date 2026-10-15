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

import io.cloudflight.pgk.proxy.search.api.port.FindRoleByTokenPort
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleCatalogEntry
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleChipOption
import org.springframework.stereotype.Component

@Component
class RoleChipMapper(
    private val findRoleByToken: FindRoleByTokenPort
) {

    fun toChipOptions(tokens: List<String>): List<RoleChipOption> =
        tokens.map { token ->
            val display = findRoleByToken.findByToken(token)
                ?.display
                ?: RoleCatalogEntry.splitToken(token).second
            RoleChipOption(token, display)
        }
}
