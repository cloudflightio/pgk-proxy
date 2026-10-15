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

import org.keycloak.Config
import org.keycloak.models.RealmModel
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Resolves how this Keycloak provider reaches the pgk-proxy. Values are read from environment
 * variables first, because the SPI id contains dashes which make the `KC_SPI_*` mapping brittle; an
 * explicit [Config.Scope] value takes precedence when set.
 */
class ProxyEndpointConfig internal constructor(
    baseUrl: String,
    val secret: String,
    /** Only users of this realm are provisioned into the Provider Directory. */
   val realm: String,
    /** Matrix server domain appended to the localpart of the IDP `mxid` user attribute. */
    val matrixDomain: String,
    /** How long the display-name map fetched from the proxy is cached, in minutes. */
    val displayNameRefreshMinutes: Long
) {

    private val baseUrl: String = baseUrl.trimEnd('/')

    fun userEventsUrl(): String = baseUrl + USER_EVENTS_PATH

    fun displayNamesUrl(): String = baseUrl + DISPLAY_NAMES_PATH

    /**
     * TODO(PoC-only): remove this before delivery — production runs one proxy (and its own
     * Keycloak) per realm, so the globally configured [baseUrl]/[matrixDomain] suffice.
     * Copies this config, overriding [baseUrl] and [matrixDomain] with per-realm values when the
     * realm carries the [REALM_BASE_URL_ATTR] / [REALM_MATRIX_DOMAIN_ATTR] attributes. This lets one
     * shared Keycloak instance provision several realms through different pgk-proxy instances.
     */
    fun withRealmOverrides(realm: RealmModel?): ProxyEndpointConfig {
        if (realm == null) {
            return this
        }
        val baseUrlOverride = realm.getAttribute(REALM_BASE_URL_ATTR)
        val matrixDomainOverride = realm.getAttribute(REALM_MATRIX_DOMAIN_ATTR)
        return ProxyEndpointConfig(
            baseUrl = baseUrlOverride.takeIf { !it.isNullOrBlank() } ?: baseUrl,
            secret = secret,
            realm = realm.name,
            matrixDomain = matrixDomainOverride.takeIf { !it.isNullOrBlank() } ?: matrixDomain,
            displayNameRefreshMinutes = displayNameRefreshMinutes
        )
    }

    /** Value for the `Authorization` header used against the pgk-proxy `/internal/idp` endpoints. */
    fun authorizationHeader(): String {
        val credentials = "$USERNAME:$secret".toByteArray(StandardCharsets.UTF_8)
        return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
    }

    companion object {

        /** Username expected by the pgk-proxy HTTP Basic auth on `/internal/idp/ **`. */
        const val USERNAME = "pgk-idp"

        const val USER_EVENTS_PATH = "/internal/idp/user-events"

        const val DISPLAY_NAMES_PATH = "/internal/idp/display-names"

        /** Realm attribute overriding the pgk-proxy base URL for that realm. */
        const val REALM_BASE_URL_ATTR = "pgk.proxy-base-url"

        /** Realm attribute overriding the Matrix server domain for that realm. */
        const val REALM_MATRIX_DOMAIN_ATTR = "pgk.matrix-domain"

        private const val BASE_URL_ENV = "PGK_PROXY_BASE_URL"
        private const val SECRET_ENV = "PGK_PROXY_WEBHOOK_SECRET"
        private const val REALM_ENV = "PGK_IDP_REALM"
        private const val MATRIX_DOMAIN_ENV = "PGK_MATRIX_DOMAIN"
        private const val DISPLAYNAME_REFRESH_MINUTES_ENV = "PGK_DISPLAYNAME_REFRESH_MINUTES"

        private const val DEFAULT_BASE_URL = "http://host.docker.internal:8080"
        private const val DEFAULT_REALM = "pgk"
        private const val DEFAULT_MATRIX_DOMAIN = "matrix.traefik.me"
        private const val DEFAULT_REFRESH_MINUTES = 60L

        fun resolve(scope: Config.Scope?): ProxyEndpointConfig = ProxyEndpointConfig(
            baseUrl = firstNonBlank(scope?.get("baseUrl"), System.getenv(BASE_URL_ENV), DEFAULT_BASE_URL),
            secret = firstNonBlank(scope?.get("secret"), System.getenv(SECRET_ENV)),
            realm = firstNonBlank(scope?.get("realm"), System.getenv(REALM_ENV), DEFAULT_REALM),
            matrixDomain = firstNonBlank(
                scope?.get("matrixDomain"),
                System.getenv(MATRIX_DOMAIN_ENV),
                DEFAULT_MATRIX_DOMAIN
            ),
            displayNameRefreshMinutes = scope?.getLong("displayNameRefreshMinutes")
                ?: System.getenv(DISPLAYNAME_REFRESH_MINUTES_ENV)?.toLongOrNull()
                ?: DEFAULT_REFRESH_MINUTES
        )

        private fun firstNonBlank(vararg candidates: String?): String =
            candidates.firstOrNull { !it.isNullOrBlank() } ?: ""
    }
}
