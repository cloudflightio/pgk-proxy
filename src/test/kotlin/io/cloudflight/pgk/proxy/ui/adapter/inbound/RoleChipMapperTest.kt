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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RoleChipMapperTest {

    private val findRoleByToken = mock<FindRoleByTokenPort>()

    private val mapper = RoleChipMapper(findRoleByToken)

    @Test
    fun `maps tokens to chips using the catalog display`() {
        whenever(findRoleByToken.findByToken("https://example/cs|HAP"))
            .thenReturn(RoleCatalogEntry("https://example/cs", "HAP", "Hausarzt"))

        assertEquals(
            listOf(RoleChipOption("https://example/cs|HAP", "Hausarzt")),
            mapper.toChipOptions(listOf("https://example/cs|HAP"))
        )
    }

    @Test
    fun `falls back to the code when the token is unknown`() {
        whenever(findRoleByToken.findByToken("https://example/cs|UNKNOWN")).thenReturn(null)

        assertEquals(
            listOf(RoleChipOption("https://example/cs|UNKNOWN", "UNKNOWN")),
            mapper.toChipOptions(listOf("https://example/cs|UNKNOWN"))
        )
    }

    @Test
    fun `maps an empty list to an empty list`() {
        assertEquals(emptyList<RoleChipOption>(), mapper.toChipOptions(emptyList()))
    }
}
