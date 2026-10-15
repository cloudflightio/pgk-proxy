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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class MxidTelecomTest {

    @Test
    @DisplayName("An mxid is encoded as a matrix telecom value")
    fun encodesMxid() {
        assertEquals("matrix:u/max:matrix.traefik.me", MxidTelecom.toTelecomValue(Mxid("@max:matrix.traefik.me")))
    }

    @Test
    @DisplayName("Encoding keeps the characters a Matrix localpart may contain")
    fun encodesSpecialLocalpartCharacters() {
        assertEquals(
            "matrix:u/john.doe_=x/y:hospital.at",
            MxidTelecom.toTelecomValue(Mxid("@john.doe_=x/y:hospital.at"))
        )
    }

    @Test
    @DisplayName("A matrix telecom value is decoded back into the mxid it was built from")
    fun decodesTelecomValue() {
        assertEquals(MxidTelecom.extractMxid("matrix:u/max:matrix.traefik.me"), Mxid("@max:matrix.traefik.me"))
    }

    @Test
    @DisplayName("Encoding and decoding are inverse operations")
    fun roundTripsMxid() {
        val mxid = Mxid("@monika:matrix.traefik.me")

        assertEquals(mxid, MxidTelecom.extractMxid(MxidTelecom.toTelecomValue(mxid)))
    }

    @Test
    @DisplayName("A telecom value that is not a matrix address is not an mxid")
    fun rejectsOtherTelecomValues() {
        assertNull(MxidTelecom.extractMxid("mailto:max@example.com"))
        assertNull(MxidTelecom.extractMxid("tel:+43-1-555"))
    }

    @Test
    @DisplayName("A matrix telecom value without a domain is rejected")
    fun rejectsValueWithoutDomain() {
        assertNull(MxidTelecom.extractMxid("matrix:u/max"))
        assertNull(MxidTelecom.extractMxid("matrix:u/max:localhost"))
    }

    @Test
    @DisplayName("A missing telecom value is not an mxid")
    fun rejectsNull() {
        assertNull(MxidTelecom.extractMxid(null))
    }
}
