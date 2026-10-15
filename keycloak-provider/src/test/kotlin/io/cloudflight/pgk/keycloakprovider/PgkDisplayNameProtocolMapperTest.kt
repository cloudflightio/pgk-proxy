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

import io.mockk.every
import io.mockk.mockk
import java.util.HashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.keycloak.models.ClientSessionContext
import org.keycloak.models.KeycloakSession
import org.keycloak.models.ProtocolMapperModel
import org.keycloak.models.UserModel
import org.keycloak.models.UserSessionModel
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.representations.IDToken

class PgkDisplayNameProtocolMapperTest {

    private val displayNameProvider = mockk<DisplayNameProvider>()
    private val mapper = PgkDisplayNameProtocolMapper(displayNameProvider)

    private val user = mockk<UserModel>()
    private val userSession = mockk<UserSessionModel>()
    private val token = IDToken()

    private fun userWithMxid(localpart: String?) {
        every { userSession.user } returns user
        every { user.username } returns "max"
        every { user.getFirstAttribute("mxid") } returns localpart
    }

    private fun mapperModel(claimName: String? = null) = ProtocolMapperModel().apply {
        config = HashMap<String, String>().apply {
            put("id.token.claim", "true")
            if (claimName != null) put("claim.name", claimName)
        }
    }

    private fun issueToken(claimName: String? = null) {
        mapper.transformIDToken(
            token,
            mapperModel(claimName),
            mockk<KeycloakSession>(),
            userSession,
            mockk<ClientSessionContext>()
        )
    }

    @Test
    @DisplayName("Sets the display name claim of a user with an mxid")
    fun setsDisplayNameClaim() {
        userWithMxid("max")
        every { displayNameProvider.displayNameFor("max") } returns "Mustermann, Max (AKH Wien)"

        issueToken()

        assertEquals("Mustermann, Max (AKH Wien)", token.otherClaims["displayname"])
    }

    @Test
    @DisplayName("Uses the configured claim name")
    fun usesConfiguredClaimName() {
        userWithMxid("max")
        every { displayNameProvider.displayNameFor("max") } returns "Mustermann, Max (AKH Wien)"

        issueToken("display_name")

        assertTrue(token.otherClaims.containsKey("display_name"))
        assertFalse(token.otherClaims.containsKey("displayname"))
    }

    @Test
    @DisplayName("Leaves the token untouched when the user has no mxid")
    fun skipsUsersWithoutMxid() {
        userWithMxid(null)

        issueToken()

        assertTrue(token.otherClaims.isEmpty())
    }

    @Test
    @DisplayName("Leaves the token untouched when no display name is known")
    fun skipsUsersWithoutDisplayName() {
        userWithMxid("max")
        every { displayNameProvider.displayNameFor("max") } returns null

        issueToken()

        assertTrue(token.otherClaims.isEmpty())
    }

    @Test
    @DisplayName("Exposes the token claim flags with a default of true")
    fun exposesTokenClaimFlags() {
        val config = mapper.getConfigProperties().associateBy { it.name }

        for (flag in listOf(
            "id.token.claim",
            "access.token.claim",
            "userinfo.token.claim",
            "introspection.token.claim"
        )) {
            assertEquals(ProviderConfigProperty.BOOLEAN_TYPE, config.getValue(flag).type)
            assertEquals("true", config.getValue(flag).defaultValue)
        }
    }
}
