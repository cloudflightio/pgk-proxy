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

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.keycloak.events.Event
import org.keycloak.events.admin.AdminEvent
import org.keycloak.events.admin.OperationType
import org.keycloak.events.admin.ResourceType
import org.keycloak.models.KeycloakSession
import org.keycloak.models.KeycloakTransaction
import org.keycloak.models.KeycloakTransactionManager
import org.keycloak.models.RealmModel
import org.keycloak.models.RealmProvider
import org.keycloak.models.UserModel
import org.keycloak.models.UserProvider

class PgkIdpProvisioningEventListenerTest {

    private val realmId = "5f0f0f0f-0000-4000-8000-00000000000a"
    private val userId = "1b7d4a4e-0000-4000-8000-000000000001"

    private val session = mockk<KeycloakSession>()
    private val transactionManager = mockk<KeycloakTransactionManager>(relaxed = true)
    private val realmProvider = mockk<RealmProvider>()
    private val userProvider = mockk<UserProvider>()
    private val realm = mockk<RealmModel>()
    private val proxyClient = mockk<ProxyClient>(relaxed = true)

    private val listener = PgkIdpProvisioningEventListener(session, proxyClient, "pgk")

    private val enlistedTransaction = slot<KeycloakTransaction>()

    @BeforeEach
    fun setUp() {
        every { session.transactionManager } returns transactionManager
        every { session.realms() } returns realmProvider
        every { session.users() } returns userProvider
        every { realmProvider.getRealm(realmId) } returns realm
        every { transactionManager.enlistAfterCompletion(capture(enlistedTransaction)) } just Runs
    }

    private fun adminEvent(
        operation: OperationType = OperationType.CREATE,
        resourceType: ResourceType = ResourceType.USER,
        resourcePath: String = "users/$userId",
        realmName: String = "pgk"
    ): AdminEvent = AdminEvent().apply {
        this.realmId = this@PgkIdpProvisioningEventListenerTest.realmId
        this.realmName = realmName
        this.operationType = operation
        this.resourceType = resourceType
        this.resourcePath = resourcePath
    }

    private fun idpUser(
        firstName: String? = "Max",
        lastName: String? = "Mustermann",
        mxid: String? = "max"
    ): UserModel = mockk<UserModel>().also {
        every { it.username } returns "max"
        every { it.firstName } returns firstName
        every { it.lastName } returns lastName
        every { it.getFirstAttribute(PgkIdpProvisioningEventListener.MXID_ATTRIBUTE) } returns mxid
        every { userProvider.getUserById(realm, userId) } returns it
    }

    /** Runs the transaction the listener enlisted, as Keycloak does after a successful admin action. */
    private fun commitEnlistedTransaction() {
        enlistedTransaction.captured.begin()
        enlistedTransaction.captured.commit()
    }

    @Test
    @DisplayName("Adding a user in the PGK realm forwards the user with mxid")
    fun forwardsCreatedUser() {
        idpUser()
        val payload = slot<IdpUserEventPayload>()

        listener.onEvent(adminEvent(), false)
        commitEnlistedTransaction()

        verify { proxyClient.sendUserEvent(capture(payload)) }
        assertEquals("CREATE", payload.captured.operation)
        assertEquals(userId, payload.captured.userId)
        assertEquals("max", payload.captured.username)
        assertEquals("Max", payload.captured.firstName)
        assertEquals("Mustermann", payload.captured.lastName)
        assertEquals("max", payload.captured.mxid)
    }

    @Test
    @DisplayName("Changing a user forwards the state the session holds after the change")
    fun forwardsUpdatedUser() {
        idpUser(firstName = "Maximilian", mxid = "maxi")
        val payload = slot<IdpUserEventPayload>()

        listener.onEvent(adminEvent(operation = OperationType.UPDATE), false)
        commitEnlistedTransaction()

        verify { proxyClient.sendUserEvent(capture(payload)) }
        assertEquals("UPDATE", payload.captured.operation)
        assertEquals("Maximilian", payload.captured.firstName)
        assertEquals("maxi", payload.captured.mxid)
    }

    @Test
    @DisplayName("Deleting a user forwards nothing but the operation and the user id")
    fun forwardsDeletedUser() {
        val payload = slot<IdpUserEventPayload>()

        listener.onEvent(adminEvent(operation = OperationType.DELETE), false)
        commitEnlistedTransaction()

        verify { proxyClient.sendUserEvent(capture(payload)) }
        assertEquals("DELETE", payload.captured.operation)
        assertEquals(userId, payload.captured.userId)
        assertEquals(null, payload.captured.mxid)
        verify(exactly = 0) { userProvider.getUserById(any(), any()) }
    }

    @Test
    @DisplayName("Nothing is sent before the administration transaction has committed")
    fun defersDeliveryUntilCommit() {
        idpUser()

        listener.onEvent(adminEvent(), false)

        verify(exactly = 0) { proxyClient.sendUserEvent(any()) }
        verify { transactionManager.enlistAfterCompletion(any()) }
    }

    @Test
    @DisplayName("Users of another realm are not provisioned")
    fun ignoresOtherRealms() {
        listener.onEvent(adminEvent(realmName = "master"), false)

        verify(exactly = 0) { transactionManager.enlistAfterCompletion(any()) }
    }

    @Test
    @DisplayName("A user that is gone by the time the event is handled is skipped")
    fun ignoresVanishedUser() {
        every { userProvider.getUserById(realm, userId) } returns null

        listener.onEvent(adminEvent(), false)

        verify(exactly = 0) { transactionManager.enlistAfterCompletion(any()) }
    }

    @Test
    @DisplayName("Login events of users are not provisioning events")
    fun ignoresUserLoginEvents() {
        listener.onEvent(Event())

        verify(exactly = 0) { transactionManager.enlistAfterCompletion(any()) }
    }

    @Test
    @DisplayName("Only create, update and delete of a user are provisioning events")
    fun recognizesUserLifecycleEvents() {
        assertTrue(PgkIdpProvisioningEventListener.isUserLifecycleEvent(adminEvent()))
        assertTrue(
            PgkIdpProvisioningEventListener.isUserLifecycleEvent(adminEvent(operation = OperationType.UPDATE))
        )
        assertTrue(
            PgkIdpProvisioningEventListener.isUserLifecycleEvent(adminEvent(operation = OperationType.DELETE))
        )
        assertFalse(
            PgkIdpProvisioningEventListener.isUserLifecycleEvent(adminEvent(operation = OperationType.ACTION))
        )
    }

    @Test
    @DisplayName("Changes to other resources than users are ignored")
    fun ignoresOtherResourceTypes() {
        assertFalse(
            PgkIdpProvisioningEventListener.isUserLifecycleEvent(
                adminEvent(resourceType = ResourceType.CLIENT, resourcePath = "clients/$userId")
            )
        )
    }

    @Test
    @DisplayName("Changes to a sub resource of a user, such as its role mappings, are ignored")
    fun ignoresUserSubResources() {
        assertFalse(
            PgkIdpProvisioningEventListener.isUserLifecycleEvent(
                adminEvent(resourcePath = "users/$userId/role-mappings/realm")
            )
        )
    }
}
