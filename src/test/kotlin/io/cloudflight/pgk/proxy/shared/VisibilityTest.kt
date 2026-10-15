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

package io.cloudflight.pgk.proxy.shared

import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Meta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisibilityTest {

    @Test
    fun `maps known codes to enum values`() {
        assertEquals(Visibility.PUBLIC, Visibility.fromCode("public"))
        assertEquals(Visibility.INTERNAL, Visibility.fromCode("internal"))
    }

    @Test
    fun `defaults to public for unknown codes`() {
        assertEquals(Visibility.PUBLIC, Visibility.fromCode("unknown"))
    }

    @Test
    fun `reads visibility from meta security tags`() {
        val meta = Meta().apply {
            addSecurity(Coding().setSystem(Visibility.SYSTEM).setCode("internal"))
        }

        assertEquals(Visibility.INTERNAL, Visibility.fromMeta(meta))
    }

    @Test
    fun `defaults to public when no security tag is present`() {
        assertEquals(Visibility.PUBLIC, Visibility.fromMeta(null))
        assertEquals(Visibility.PUBLIC, Visibility.fromMeta(Meta()))
    }
}
