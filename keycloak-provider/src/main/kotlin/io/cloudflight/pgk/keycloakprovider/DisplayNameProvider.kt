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

import java.util.logging.Logger

/**
 * Resolves the chat display name of an IDP user from the Provider Directory, served by the
 * pgk-proxy. The fetched name map is cached for [refreshMinutes]; a value `<= 0` disables caching.
 * Runs inside the token-issuance flow, so it must never throw - the proxy client swallows errors
 * and reports an empty map instead.
 */
class DisplayNameProvider(
    private val proxyClient: ProxyClient,
    private val matrixDomain: String,
    private val refreshMinutes: Long
) {

    @Volatile
    private var cached: Map<String, String>? = null

    @Volatile
    private var fetchedAtMillis: Long = 0

    fun displayNameFor(localpart: String): String? =
        currentNames()["@$localpart:$matrixDomain"]

    @Synchronized
    private fun currentNames(): Map<String, String> {
        cached?.let { names ->
            if (refreshMinutes > 0 && System.currentTimeMillis() - fetchedAtMillis < refreshMinutes * MILLIS_PER_MINUTE) {
                return names
            }
        }
        val fresh = proxyClient.fetchDisplayNames()
        if (fresh.isEmpty()) {
            LOG.warning(
                "PGK display-name provider: proxy returned no display names, " +
                    "will retry on the next call"
            )
            return emptyMap()
        }
        cached = fresh
        fetchedAtMillis = System.currentTimeMillis()
        LOG.info(
            "PGK display-name provider: fetched ${fresh.size} display name(s), " +
                "sample=${fresh.entries.firstOrNull()?.let { "${it.key} -> ${it.value}" }}"
        )
        return fresh
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        private val LOG = Logger.getLogger(DisplayNameProvider::class.java.name)
    }
}
