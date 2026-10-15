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
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.keycloak.Config
import org.keycloak.models.RealmModel

class ProxyEndpointConfigTest {

    private fun config(baseUrl: String = "http://proxy:8080") =
        ProxyEndpointConfig(baseUrl, "secret", "pgk", "matrix.traefik.me", 60)

    @Test
    @DisplayName("The user event endpoint is built from the configured base URL")
    fun buildsUserEventsUrl() {
        assertEquals("http://proxy:8080/internal/idp/user-events", config().userEventsUrl())
    }

    @Test
    @DisplayName("The display-name endpoint is built from the configured base URL")
    fun buildsDisplayNamesUrl() {
        assertEquals("http://proxy:8080/internal/idp/display-names", config().displayNamesUrl())
    }

    @Test
    @DisplayName("A trailing slash in the base URL does not produce a double slash")
    fun normalizesTrailingSlash() {
        assertEquals(
            "http://proxy:8080/internal/idp/user-events",
            config("http://proxy:8080//").userEventsUrl()
        )
    }

    @Test
    @DisplayName("The authorization header carries the basic credentials")
    fun buildsAuthorizationHeader() {
        assertEquals(
            "Basic ${Base64.getEncoder().encodeToString("pgk-idp:secret".toByteArray())}",
            config().authorizationHeader()
        )
    }

    @Test
    @DisplayName("Explicit SPI configuration takes precedence over the environment")
    fun prefersScopedConfiguration() {
        val scope = mockk<Config.Scope>()
        every { scope.get("baseUrl") } returns "http://configured:9000"
        every { scope.get("secret") } returns "configured-secret"
        every { scope.get("realm") } returns "configured-realm"
        every { scope.get("matrixDomain") } returns "configured.domain"
        every { scope.getLong("displayNameRefreshMinutes") } returns 30L

        val resolved = ProxyEndpointConfig.resolve(scope)

        assertEquals("http://configured:9000/internal/idp/user-events", resolved.userEventsUrl())
        assertEquals("http://configured:9000/internal/idp/display-names", resolved.displayNamesUrl())
        assertEquals("configured-secret", resolved.secret)
        assertEquals("configured-realm", resolved.realm)
        assertEquals("configured.domain", resolved.matrixDomain)
        assertEquals(30L, resolved.displayNameRefreshMinutes)
    }

    @Test
    @DisplayName("Without any configuration the provider talks to the proxy on the docker host")
    fun fallsBackToDefaults() {
        val scope = mockk<Config.Scope>()
        every { scope.get(any<String>()) } returns null
        every { scope.getLong(any<String>()) } returns null

        val resolved = ProxyEndpointConfig.resolve(scope)

        assertEquals("http://host.docker.internal:8080/internal/idp/user-events", resolved.userEventsUrl())
        assertEquals("pgk", resolved.realm)
        assertEquals("matrix.traefik.me", resolved.matrixDomain)
        assertEquals(60L, resolved.displayNameRefreshMinutes)
    }

    @Test
    @DisplayName("Realm attributes override the base URL, matrix domain and realm for that realm")
    fun realmAttributesOverrideConfig() {
        val realm = mockk<RealmModel>()
        every { realm.name } returns "pgk-c"
        every { realm.getAttribute(ProxyEndpointConfig.REALM_BASE_URL_ATTR) } returns "http://pgk-proxy-c:8080"
        every { realm.getAttribute(ProxyEndpointConfig.REALM_MATRIX_DOMAIN_ATTR) } returns "synapse-c-pgkpoc.apps.ocedev01.wien.at"

        val overridden = config().withRealmOverrides(realm)

        assertEquals("http://pgk-proxy-c:8080/internal/idp/user-events", overridden.userEventsUrl())
        assertEquals("http://pgk-proxy-c:8080/internal/idp/display-names", overridden.displayNamesUrl())
        assertEquals("synapse-c-pgkpoc.apps.ocedev01.wien.at", overridden.matrixDomain)
        assertEquals("pgk-c", overridden.realm)
    }

    @Test
    @DisplayName("A realm without overrides keeps the configured values")
    fun realmWithoutOverridesKeepsConfig() {
        val realm = mockk<RealmModel>()
        every { realm.name } returns "pgk"
        every { realm.getAttribute(any<String>()) } returns null

        val overridden = config().withRealmOverrides(realm)

        assertEquals("http://proxy:8080/internal/idp/user-events", overridden.userEventsUrl())
        assertEquals("matrix.traefik.me", overridden.matrixDomain)
        assertEquals("pgk", overridden.realm)
    }
}
