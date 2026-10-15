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

package io.cloudflight.pgk.keycloakprovider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IdpUserEventPayloadTest {

    @Test
    @DisplayName("A user event is serialized with every field of the proxy request")
    fun serializesAllFields() {
        val payload = IdpUserEventPayload(
            operation = "CREATE",
            userId = "user-1",
            username = "max",
            firstName = "Max",
            lastName = "Mustermann",
            mxid = "max"
        )

        assertEquals(
            """{"operation":"CREATE","userId":"user-1","username":"max",""" +
                """"firstName":"Max","lastName":"Mustermann","mxid":"max"}""",
            payload.toJson()
        )
    }

    @Test
    @DisplayName("Fields Keycloak does not provide are serialized as null")
    fun serializesMissingFieldsAsNull() {
        val payload = IdpUserEventPayload(operation = "DELETE", userId = "user-1")

        assertEquals(
            """{"operation":"DELETE","userId":"user-1","username":null,""" +
                """"firstName":null,"lastName":null,"mxid":null}""",
            payload.toJson()
        )
    }

    @Test
    @DisplayName("A name containing quotes or backslashes does not break the JSON body")
    fun escapesQuotesAndBackslashes() {
        val payload = IdpUserEventPayload(
            operation = "CREATE",
            userId = "user-1",
            lastName = "O\"Brien\\Smith"
        )

        assertTrue(payload.toJson().contains("\"lastName\":\"O\\\"Brien\\\\Smith\""))
    }

    @Test
    @DisplayName("Line breaks and tabs are escaped")
    fun escapesWhitespaceControlCharacters() {
        assertEquals("a\\nb\\rc\\td", IdpUserEventPayload.escape("a\nb\rc\td"))
    }

    @Test
    @DisplayName("Other control characters are escaped as unicode sequences")
    fun escapesRemainingControlCharacters() {
        assertEquals("\\u0000", IdpUserEventPayload.escape(Char(0).toString()))
        assertEquals("\\u001f", IdpUserEventPayload.escape(Char(0x1f).toString()))
    }

    @Test
    @DisplayName("Umlauts are kept as they are")
    fun keepsNonAsciiCharacters() {
        assertEquals("Müller", IdpUserEventPayload.escape("Müller"))
    }
}
