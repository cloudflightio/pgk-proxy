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

package io.cloudflight.pgk.proxy.provisioning.application

import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUserOperation
import io.cloudflight.pgk.proxy.provisioning.port.FindUserIdsByMxidPort
import io.cloudflight.pgk.proxy.provisioning.port.RemovePractitionerPort
import io.cloudflight.pgk.proxy.provisioning.port.WritePractitionerPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IdpProvisioningServiceTest {
    private val write = mockk<WritePractitionerPort>(relaxed = true)
    private val remove = mockk<RemovePractitionerPort>(relaxed = true)
    private val byMxid = mockk<FindUserIdsByMxidPort>()
    private val service = IdpProvisioningService(write, remove, byMxid, AuditLogger())
    private val mxid = Mxid("@max:example.org")
    private val user = IdpUser("kc-1", "max", "Max", "Mustermann", mxid)

    @Test
    fun `creates or updates the practitioner resource`() {
        every { byMxid.findUserIdsByMxid(mxid) } returns emptyList()

        service.apply(user, IdpUserOperation.UPDATE)

        verify { write.write(user) }
        verify(exactly = 0) { remove.remove(any()) }
    }

    @Test
    fun `removes the practitioner when mxid is cleared on update`() {
        service.apply(user.copy(mxid = null), IdpUserOperation.UPDATE)

        verify { remove.remove(user.userId) }
        verify(exactly = 0) { write.write(any()) }
    }

    @Test
    fun `does not remove the practitioner on create without mxid`() {
        val userWithoutMxid = user.copy(mxid = null)

        service.apply(userWithoutMxid, IdpUserOperation.CREATE)

        verify(exactly = 0) { remove.remove(any()) }
    }

    @Test
    fun `removes the practitioner when user is deleted`() {
        service.apply(user, IdpUserOperation.DELETE)

        verify { remove.remove(user.userId) }
        verify(exactly = 0) { write.write(any()) }
    }

    @Test
    fun `allows mxid already assigned to the same practitioner`() {
        every { byMxid.findUserIdsByMxid(mxid) } returns listOf(user.userId)

        service.apply(user, IdpUserOperation.UPDATE)

        verify { write.write(user) }
    }

    @Test
    fun `rejects an mxid assigned to another practitioner`() {
        every { byMxid.findUserIdsByMxid(mxid) } returns listOf("other")

        assertThrows(IllegalArgumentException::class.java) {
            service.apply(user, IdpUserOperation.UPDATE)
        }

        verify(exactly = 0) { write.write(any()) }
        verify(exactly = 0) { remove.remove(any()) }
    }
}
