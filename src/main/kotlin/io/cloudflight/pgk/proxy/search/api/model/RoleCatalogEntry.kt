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

package io.cloudflight.pgk.proxy.search.api.domain.model

data class RoleCatalogEntry(
    val system: String,
    val code: String,
    val display: String,
    val title: String? = null,
    val retired: Boolean = false
) {
    val token: String get() = tokenOf(system, code)

    companion object {
        fun tokenOf(system: String?, code: String?): String = "${system.orEmpty()}|${code.orEmpty()}"

        fun splitToken(token: String): Pair<String?, String> {
            val separator = token.lastIndexOf('|')
            if (separator < 0) return null to token
            return token.substring(0, separator).ifBlank { null } to token.substring(separator + 1)
        }
    }
}
