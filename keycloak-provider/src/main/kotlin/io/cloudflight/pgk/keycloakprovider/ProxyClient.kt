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

import com.fasterxml.jackson.core.type.TypeReference
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import org.keycloak.util.JsonSerialization

/**
 * Talks to the pgk-proxy "/internal/idp" endpoints. Delivery is deliberately forgiving:
 * administering users in Keycloak must never fail because the proxy or the Provider Directory is
 * unavailable, so errors are logged and swallowed.
 */
class ProxyClient(
    private val config: ProxyEndpointConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
) {

    fun sendUserEvent(payload: IdpUserEventPayload) {
        try {
            val request = HttpRequest.newBuilder(URI.create(config.userEventsUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", config.authorizationHeader())
                .POST(HttpRequest.BodyPublishers.ofString(payload.toJson()))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 300) {
                LOG.warning(
                    "pgk-proxy rejected user event for userId=${payload.userId}: " +
                        "HTTP ${response.statusCode()} ${response.body()}"
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.log(Level.WARNING, "Interrupted while notifying pgk-proxy", e)
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to notify pgk-proxy about user ${payload.userId}", e)
        }
    }

    /**
     * Fetches the chat display name of every practitioner from the proxy, keyed by the full Matrix
     * user id (`@localpart:domain`). Returns an empty map when the proxy cannot be reached.
     */
    fun fetchDisplayNames(): Map<String, String> {
        return try {
            val request = HttpRequest.newBuilder(URI.create(config.displayNamesUrl()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", config.authorizationHeader())
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 300) {
                LOG.warning("pgk-proxy display-name request failed: HTTP ${response.statusCode()}")
                emptyMap()
            } else {
                JsonSerialization.readValue(response.body(), object : TypeReference<Map<String, String>>() {})
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.log(Level.WARNING, "Interrupted while fetching display names from pgk-proxy", e)
            emptyMap()
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Failed to fetch display names from pgk-proxy", e)
            emptyMap()
        }
    }

    companion object {

        private val LOG: Logger = Logger.getLogger(ProxyClient::class.java.name)

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
