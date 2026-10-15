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
import org.keycloak.models.ClientSessionContext
import org.keycloak.models.KeycloakSession
import org.keycloak.models.ProtocolMapperModel
import org.keycloak.models.UserSessionModel
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper
import org.keycloak.provider.ProviderConfigProperty
import org.keycloak.representations.IDToken
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Adds the user's chat display name as a claim to every issued token. The name is computed on
 * demand from the Provider Directory (served by the pgk-proxy) at token issuance time, so it is
 * always in sync with the directory - no scheduler involved. The proxied name map is cached for
 * the configured refresh interval, read via time-based logic on each call.
 *
 * Registered as the Keycloak protocol mapper SPI `pgk-display-name-mapper` (see
 * META-INF/services/org.keycloak.protocol.ProtocolMapper). Implements the token-mapper marker
 * interfaces, otherwise Keycloak silently skips this mapper when generating tokens (TokenManager
 * filters `getSortedProtocolMappers` by `instanceof OIDC...TokenMapper`).
 */
class PgkDisplayNameProtocolMapper() :
    AbstractOIDCProtocolMapper(),
    OIDCIDTokenMapper,
    OIDCAccessTokenMapper,
    UserInfoTokenMapper,
    TokenIntrospectionTokenMapper {

    private lateinit var displayNameProvider: DisplayNameProvider

    /** Set when [init] runs (Keycloak runtime); null when constructed for tests via [provider]. */
    private var defaultConfig: ProxyEndpointConfig? = null

    private val realmProviders = ConcurrentHashMap<String, DisplayNameProvider>()

    internal constructor(provider: DisplayNameProvider) : this() {
        displayNameProvider = provider
    }

    override fun init(config: Config.Scope?) {
        super.init(config)
        val endpointConfig = ProxyEndpointConfig.resolve(config)
        defaultConfig = endpointConfig
        LOG.info(
            "PGK display-name mapper initialized against " +
                "${endpointConfig.userEventsUrl().removeSuffix(ProxyEndpointConfig.USER_EVENTS_PATH)} " +
                "(matrixDomain=${endpointConfig.matrixDomain}, refreshMinutes=${endpointConfig.displayNameRefreshMinutes})"
        )
        displayNameProvider = DisplayNameProvider(
            proxyClient = ProxyClient(endpointConfig),
            matrixDomain = endpointConfig.matrixDomain,
            refreshMinutes = endpointConfig.displayNameRefreshMinutes
        )
    }

    override fun setClaim(
        token: IDToken,
        mappingModel: ProtocolMapperModel,
        userSession: UserSessionModel,
        keycloakSession: KeycloakSession,
        clientSessionCtx: ClientSessionContext
    ) {
        val username = userSession.user.username
        val localpart = userSession.user.getFirstAttribute(MXID_ATTRIBUTE)
        LOG.info("PGK display-name mapper: user=$username mxidAttribute=$localpart")
        if (localpart == null) {
            LOG.info("PGK display-name mapper: no mxid attribute, skipping user=$username")
            return
        }
        val displayName = displayNameProviderFor(keycloakSession).displayNameFor(localpart)
        LOG.info("PGK display-name mapper: resolved display name for user=$username: $displayName")
        if (displayName == null) {
            LOG.info("PGK display-name mapper: no display name known, skipping user=$username")
            return
        }
        val claimName = mappingModel.config[CLAIM_NAME] ?: DEFAULT_CLAIM_NAME
        token.otherClaims[claimName] = displayName
        LOG.info("PGK display-name mapper: set claim $claimName=$displayName for user=$username")
    }

    /**
     * TODO(PoC-only): remove this per-realm resolution before delivery — production runs one proxy
     * (and its own Keycloak) per realm, so [displayNameProvider] suffices.
     * Returns the display-name provider for the current realm, falling back to the globally
     * configured one. Used only while several realms share a single Keycloak instance (PoC).
     */
    private fun displayNameProviderFor(keycloakSession: KeycloakSession): DisplayNameProvider {
        val base = defaultConfig ?: return displayNameProvider
        val realm = keycloakSession.context.realm ?: return displayNameProvider
        return realmProviders.computeIfAbsent(realm.name) {
            val overridden = base.withRealmOverrides(realm)
            DisplayNameProvider(
                proxyClient = ProxyClient(overridden),
                matrixDomain = overridden.matrixDomain,
                refreshMinutes = overridden.displayNameRefreshMinutes
            )
        }
    }

    override fun getId(): String = PROVIDER_ID

    override fun getDisplayType(): String = "PGK Display Name"

    override fun getDisplayCategory(): String = "PGK"

    override fun getHelpText(): String =
        "Adds the chat display name from the Provider Directory as a claim, computed at token issuance."

    override fun getConfigProperties(): List<ProviderConfigProperty> {
        val configProperties = mutableListOf<ProviderConfigProperty>()
        configProperties.add(ProviderConfigProperty().apply {
            name = CLAIM_NAME
            label = "Claim name"
            helpText = "Name of the claim holding the display name."
            type = ProviderConfigProperty.STRING_TYPE
            defaultValue = DEFAULT_CLAIM_NAME
        })
        OIDCAttributeMapperHelper.addIncludeInTokensConfig(configProperties, javaClass)
        return configProperties
    }

    companion object {
        const val PROVIDER_ID = "pgk-display-name-mapper"

        const val CLAIM_NAME = "claim.name"

        const val DEFAULT_CLAIM_NAME = "displayname"

        private const val MXID_ATTRIBUTE = "mxid"

        private val LOG = Logger.getLogger(PgkDisplayNameProtocolMapper::class.java.name)
    }
}
