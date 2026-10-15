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

import org.keycloak.events.Event
import org.keycloak.events.EventListenerProvider
import org.keycloak.events.admin.AdminEvent
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.AbstractKeycloakTransaction
import org.keycloak.models.KeycloakSession
import org.keycloak.models.RealmModel

/**
 * Forwards user administration events of the PGK realm to the pgk-proxy, which owns the FHIR
 * Practitioner lifecycle in the Provider Directory.
 */
class PgkIdpProvisioningEventListener(
    private val session: KeycloakSession,
    private val defaultProxyClient: ProxyClient,
    private val provisionedRealm: String,
    /**
     * Resolves the pgk-proxy for a realm that differs from the globally configured one. Used only
     * while several realms share a single Keycloak instance (PoC); per-realm proxies fall back to
     * [defaultProxyClient].
     *
     * TODO(PoC-only): remove this parameter and the per-realm routing below before delivery. In
     * production each proxy runs its own Keycloak, so the global config is sufficient.
     */
    private val realmProxyClient: (RealmModel) -> ProxyClient? = { null }
) : EventListenerProvider {

    override fun onEvent(event: Event?) {
    }

    override fun onEvent(event: AdminEvent?, includeRepresentation: Boolean) {
        if (event == null || !isUserLifecycleEvent(event)) {
            return
        }

        val realm = session.realms().getRealm(event.realmId) ?: return
        val proxyClient =
            if (event.realmName == provisionedRealm) defaultProxyClient else realmProxyClient(realm) ?: return

        val payload = buildPayload(event, realm) ?: return

        // Deliver only once the administration transaction has committed, so the Provider Directory
        // is never told about a change that is subsequently rolled back.
        session.transactionManager.enlistAfterCompletion(object : AbstractKeycloakTransaction() {
            override fun commitImpl() {
                proxyClient.sendUserEvent(payload)
            }

            override fun rollbackImpl() {
            }
        })
    }

    private fun buildPayload(event: AdminEvent, realm: RealmModel): IdpUserEventPayload? {
        val userId = event.resourceId
        if (userId.isNullOrBlank()) {
            return null
        }

        if (event.operationType == OperationType.DELETE) {
            return IdpUserEventPayload(
                operation = OperationType.DELETE.name,
                userId = userId
            )
        }

        val user = session.users().getUserById(realm, userId) ?: return null

        return IdpUserEventPayload(
            operation = event.operationType.name,
            userId = userId,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            mxid = user.getFirstAttribute(MXID_ATTRIBUTE)
        )
    }

    override fun close() {
    }

    companion object {

        const val MXID_ATTRIBUTE = "mxid"

        private val USER_RESOURCE_PATH = Regex("^users/[^/]+$")

        fun isUserLifecycleEvent(event: AdminEvent): Boolean {
            if (event.resourceType != ResourceType.USER) {
                return false
            }
            if (event.resourcePath?.matches(USER_RESOURCE_PATH) != true) {
                return false
            }
            return event.operationType == OperationType.CREATE ||
                event.operationType == OperationType.UPDATE ||
                event.operationType == OperationType.DELETE
        }
    }
}
