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
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.EventListenerProviderFactory
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakSessionFactory
import org.keycloak.models.RealmModel

/**
 * Registers the PGK provisioning listener under the id `pgk-idp-provisioning`. Enable it by adding
 * that id to the realm's `eventsListeners`.
 */
class PgkIdpProvisioningEventListenerProviderFactory : EventListenerProviderFactory {

    private lateinit var defaultProxyClient: ProxyClient
    private lateinit var defaultConfig: ProxyEndpointConfig
    private lateinit var provisionedRealm: String

    override fun create(session: KeycloakSession): EventListenerProvider {
        if (!::defaultProxyClient.isInitialized) {
            init(null)
        }
        return PgkIdpProvisioningEventListener(
            session,
            defaultProxyClient,
            provisionedRealm,
            ::resolveProxyClient
        )
    }

    override fun init(config: Config.Scope?) {
        defaultConfig = ProxyEndpointConfig.resolve(config)
        defaultProxyClient = ProxyClient(defaultConfig)
        provisionedRealm = defaultConfig.realm
    }

    /** TODO(PoC-only): remove this before delivery. picks the pgk-proxy for a realm configured via realm attributes (shared Keycloak). */
    private fun resolveProxyClient(realm: RealmModel): ProxyClient? {
        if (realm.getAttribute(ProxyEndpointConfig.REALM_BASE_URL_ATTR).isNullOrBlank()) {
            return null
        }
        return ProxyClient(defaultConfig.withRealmOverrides(realm))
    }

    override fun postInit(factory: KeycloakSessionFactory?) {
    }

    override fun close() {
    }

    override fun getId(): String = PROVIDER_ID

    companion object {
        const val PROVIDER_ID = "pgk-idp-provisioning"
    }
}
