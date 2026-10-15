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

/**
 * Body posted to `POST /internal/idp/user-events`. Serialised by hand rather than with Jackson so
 * that this provider carries no dependency of its own into the Keycloak runtime.
 *
 * For `DELETE` only [operation] and [userId] are populated - Keycloak has no representation
 * of a user it has just deleted, and the user is already gone from the session.
 */
data class IdpUserEventPayload(
    val operation: String,
    val userId: String,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val mxid: String? = null
) {

    fun toJson(): String = linkedMapOf(
        "operation" to operation,
        "userId" to userId,
        "username" to username,
        "firstName" to firstName,
        "lastName" to lastName,
        "mxid" to mxid
    ).entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
        "\"$key\":" + if (value == null) "null" else "\"${escape(value)}\""
    }

    companion object {

        fun escape(value: String): String = buildString(value.length + 8) {
            for (character in value) {
                when {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character.code < 0x20 -> append("\\u%04x".format(character.code))
                    else -> append(character)
                }
            }
        }
    }
}
