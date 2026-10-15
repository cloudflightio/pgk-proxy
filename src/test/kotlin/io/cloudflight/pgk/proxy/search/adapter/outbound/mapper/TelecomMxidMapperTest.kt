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

package io.cloudflight.pgk.proxy.search.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.Mxid
import org.hl7.fhir.r4.model.ContactPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TelecomMxidMapperTest {

    private val mapper = TelecomMxidMapper(ProxyProperties())

    private fun telecom(vararg values: String): List<ContactPoint> =
        values.map { ContactPoint().setSystem(ContactPoint.ContactPointSystem.URL).setValue(it) }

    @Test
    fun `converts matrix u slash value to mxid`() {
        val result = mapper.extractMxid(telecom("matrix:u/alice:example.com"))

        assertEquals(Mxid("@alice:example.com"), result)
    }

    @Test
    fun `strips prefix and substitutes at sign`() {
        val result = mapper.extractMxid(telecom("matrix:u/john.doe_=x:hospital.at"))

        assertEquals(Mxid("@john.doe_=x:hospital.at"), result)
    }

    @Test
    fun `picks the first matching entry among multiple telecoms`() {
        val result = mapper.extractMxid(
            telecom(
                "tel:+43-1-555",
                "matrix:u/bob:example.com",
                "mailto:bob@example.com"
            )
        )

        assertEquals(Mxid("@bob:example.com"), result)
    }

    @Test
    fun `returns null when no telecom matches the matrix pattern`() {
        val result = mapper.extractMxid(
            telecom(
                "tel:+43-1-555",
                "mailto:bob@example.com"
            )
        )

        assertNull(result)
    }

    @Test
    fun `returns null for an empty list`() {
        assertNull(mapper.extractMxid(emptyList()))
    }

    @Test
    fun `returns null for an already matrix style mxid without prefix`() {
        val result = mapper.extractMxid(telecom("@alice:example.com"))

        assertNull(result)
    }

    @Test
    fun `rejects invalid domains`() {
        assertNull(mapper.extractMxid(telecom("matrix:u/alice:localhost")))
    }

    @Test
    fun `rejects uppercase characters in localpart`() {
        assertNull(mapper.extractMxid(telecom("matrix:u/Alice:example.com")))
    }
}
