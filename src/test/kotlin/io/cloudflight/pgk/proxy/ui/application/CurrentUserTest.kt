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

package io.cloudflight.pgk.proxy.ui.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import java.time.Instant

class CurrentUserTest {

    private fun oidcUser(vararg authorities: String) = DefaultOidcUser(
        authorities.map { SimpleGrantedAuthority(it) },
        OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), mapOf("sub" to "user-1")),
        OidcUserInfo(mapOf("sub" to "user-1"))
    )

    @Test
    fun `from null principal yields an unknown user without admin rights`() {
        val user = CurrentUser.from(null)

        assertEquals("Unbekannt", user.name)
        assertFalse(user.admin)
    }

    @Test
    fun `maps the admin realm role to the admin flag`() {
        val user = CurrentUser.from(oidcUser("ROLE_USER", "ROLE_ADMIN"))

        assertTrue(user.admin)
        assertEquals("user-1", user.subject)
    }

    @Test
    fun `user without the admin role is not an admin`() {
        val user = CurrentUser.from(oidcUser("ROLE_USER"))

        assertFalse(user.admin)
    }
}
