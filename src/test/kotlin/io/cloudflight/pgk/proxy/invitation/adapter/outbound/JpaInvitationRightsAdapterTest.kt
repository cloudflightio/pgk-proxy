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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound

import io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence.*
import io.cloudflight.pgk.proxy.invitation.api.model.ChangeInvitationRights
import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.model.OrgBlockedRoles
import io.cloudflight.pgk.proxy.invitation.api.model.RolePermission
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerDirectoryEntry
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class JpaInvitationRightsAdapterTest {

    private val directory = mockk<PractitionerDirectoryPort>()
    private val practitionerByMxid = mockk<FindPractitionerByMxidPort>()
    private val practitionerRights = mockk<PractitionerInvitationRightsRepository>()
    private val roleBlocks = mockk<OrgRoleBlockRepository>()
    private val blockedUsers = mockk<OrgBlockedUserRepository>()
    private val adapter = JpaInvitationRightsAdapter(directory, practitionerByMxid, practitionerRights, roleBlocks, blockedUsers, AuditLogger())

    // --- Practitioner list --------------------------------------------------

    @Test
    fun `merges stored rights into directory entries`() {
        val entry = PractitionerDirectoryEntry("p-1", "Dr. Anna Berger")
        every { directory.list() } returns listOf(entry)
        every { practitionerRights.findAllById(listOf("p-1")) } returns listOf(
            PractitionerInvitationRightsEntity("p-1", receive = false, send = true)
        )

        val result = adapter.getPractitionerRights()

        assertEquals(
            listOf(InvitationRightsEntry("p-1", "Dr. Anna Berger", receive = false, send = true)),
            result
        )
    }

    @Test
    fun `defaults rows without stored rights to allowed`() {
        val entry = PractitionerDirectoryEntry("p-2", "Max Huber")
        every { directory.list() } returns listOf(entry)
        every { practitionerRights.findAllById(listOf("p-2")) } returns emptyList()

        assertEquals(
            listOf(InvitationRightsEntry("p-2", "Max Huber", receive = true, send = true)),
            adapter.getPractitionerRights()
        )
    }

    // --- Practitioner rights by mxid (enforcement) --------------------------

    @Test
    fun `looks up rights by mxid via the directory`() {
        val mxid = Mxid("@max:matrix.traefik.me")
        every { practitionerByMxid.findByMxid(mxid) } returns PractitionerDirectoryEntry("p-1", "Max Huber", mxid)
        every { practitionerRights.findById("p-1") } returns
                Optional.of(PractitionerInvitationRightsEntity("p-1", receive = true, send = false))

        val result = adapter.getPractitionerRights(mxid)

        assertEquals(
            InvitationRightsEntry("p-1", "Max Huber", mxid = mxid, receive = true, send = false),
            result
        )
    }

    @Test
    fun `by-mxid lookup defaults to allowed when the user is in the directory but has no stored row`() {
        val mxid = Mxid("@anna:matrix.traefik.me")
        every { practitionerByMxid.findByMxid(mxid) } returns PractitionerDirectoryEntry("p-5", "Anna Berger", mxid)
        every { practitionerRights.findById("p-5") } returns Optional.empty()

        assertEquals(
            InvitationRightsEntry("p-5", "Anna Berger", mxid = mxid, receive = true, send = true),
            adapter.getPractitionerRights(mxid)
        )
    }

    @Test
    fun `by-mxid lookup returns null for users outside the directory`() {
        val mxid = Mxid("@stranger:evil.example")
        every { practitionerByMxid.findByMxid(mxid) } returns null

        assertEquals(null, adapter.getPractitionerRights(mxid))
    }

    // --- Practitioner toggle ------------------------------------------------

    @Test
    fun `toggle updates only the changed direction on an existing row`() {
        every { practitionerRights.findById("p-1") } returns
                Optional.of(PractitionerInvitationRightsEntity("p-1", receive = true, send = true))
        val saved = slot<PractitionerInvitationRightsEntity>()
        every { practitionerRights.save(capture(saved)) } returnsArgument 0

        adapter.changeInvitationSetting(ChangeInvitationRights(id = "p-1", receive = null, send = false))

        assertEquals("p-1", saved.captured.practitionerId)
        assertEquals(true, saved.captured.receive)
        assertEquals(false, saved.captured.send)
        verify(exactly = 1) { practitionerRights.findById("p-1") }
    }

    @Test
    fun `toggle creates a row when none exists`() {
        every { practitionerRights.findById("p-3") } returns Optional.empty()
        val saved = slot<PractitionerInvitationRightsEntity>()
        every { practitionerRights.save(capture(saved)) } returnsArgument 0

        adapter.changeInvitationSetting(ChangeInvitationRights(id = "p-3", receive = false, send = null))

        assertEquals("p-3", saved.captured.practitionerId)
        assertEquals(false, saved.captured.receive)
        assertEquals(true, saved.captured.send)
        verify(exactly = 1) { practitionerRights.findById("p-3") }
    }

    @Test
    fun `toggling an absent row back to allowed writes nothing`() {
        every { practitionerRights.findById("p-4") } returns Optional.empty()

        adapter.changeInvitationSetting(ChangeInvitationRights(id = "p-4", receive = true, send = null))

        verify(exactly = 1) { practitionerRights.findById("p-4") }
        verify(exactly = 0) { practitionerRights.save(any()) }
    }

    @Test
    fun `toggling both rights back to allowed deletes the row`() {
        every { practitionerRights.findById("p-1") } returns
                Optional.of(PractitionerInvitationRightsEntity("p-1", receive = false, send = true))
        val deleted = slot<PractitionerInvitationRightsEntity>()
        every { practitionerRights.delete(capture(deleted)) } just Runs

        adapter.changeInvitationSetting(ChangeInvitationRights(id = "p-1", receive = true, send = null))

        assertEquals("p-1", deleted.captured.practitionerId)
        verify(exactly = 1) { practitionerRights.delete(deleted.captured) }
        verify(exactly = 0) { practitionerRights.save(any()) }
    }

    @Test
    fun `no-op setting is ignored`() {
        adapter.changeInvitationSetting(ChangeInvitationRights(id = "p-5", receive = null, send = null))

        verify(exactly = 0) { practitionerRights.findById(any()) }
        verify(exactly = 0) { practitionerRights.save(any()) }
    }

    // --- Role block lists ----------------------------------------------------

    @Test
    fun `groups role rows by direction sorted by token`() {
        every { roleBlocks.findAll() } returns listOf(
            OrgRoleBlockEntity(OrgRoleBlockId("receive", "sys|B")),
            OrgRoleBlockEntity(OrgRoleBlockId("send", "sys|D")),
            OrgRoleBlockEntity(OrgRoleBlockId("receive", "sys|A"))
        )

        val blocked: OrgBlockedRoles = adapter.getBlockedRoles()

        assertEquals(
            listOf(RolePermission("sys|A", "sys|A"), RolePermission("sys|B", "sys|B")),
            blocked.receiveBlockedRoles
        )
        assertEquals(listOf(RolePermission("sys|D", "sys|D")), blocked.sendBlockedRoles)
    }

    // --- Role block replace -------------------------------------------------

    @Test
    fun `checks a role token against the blocked set via jpa`() {
        every { roleBlocks.existsByDirectionAndRoleTokenIn("receive", setOf("sys|A", "sys|B")) } returns true

        assertTrue(adapter.isAnyRoleBlocked(setOf("sys|A", "sys|B"), InvitationDirection.INBOUND))
        verify(exactly = 1) { roleBlocks.existsByDirectionAndRoleTokenIn("receive", setOf("sys|A", "sys|B")) }
    }

    @Test
    fun `empty role tokens are never blocked`() {
        assertFalse(adapter.isAnyRoleBlocked(emptySet(), InvitationDirection.OUTBOUND))
        verify(exactly = 0) { roleBlocks.existsByDirectionAndRoleTokenIn(any(), any()) }
    }

    @Test
    fun `replacing the blocked set clears the direction and saves the new tokens`() {
        every { roleBlocks.deleteAllByDirection("receive") } just Runs
        every { roleBlocks.saveAll(any<List<OrgRoleBlockEntity>>()) } returns emptyList()

        adapter.replaceBlockedRoles(InvitationDirection.INBOUND, setOf("sys|A", "sys|B"))

        verify(exactly = 1) { roleBlocks.deleteAllByDirection("receive") }
        verify(exactly = 1) {
            roleBlocks.saveAll(
                match<List<OrgRoleBlockEntity>> { list ->
                    list.map { it.id } == listOf(OrgRoleBlockId("receive", "sys|A"), OrgRoleBlockId("receive", "sys|B"))
                            || list.map { it.id } == listOf(OrgRoleBlockId("receive", "sys|B"), OrgRoleBlockId("receive", "sys|A"))
                }
            )
        }
    }

    @Test
    fun `replacing with an empty set just clears the direction`() {
        every { roleBlocks.deleteAllByDirection("send") } just Runs

        adapter.replaceBlockedRoles(InvitationDirection.OUTBOUND, emptySet())

        verify(exactly = 1) { roleBlocks.deleteAllByDirection("send") }
        verify(exactly = 0) { roleBlocks.saveAll(any<List<OrgRoleBlockEntity>>()) }
    }

    // --- Single role toggle -------------------------------------------------

    @Test
    fun `toggling a role that is not blocked adds it`() {
        every { roleBlocks.existsById(OrgRoleBlockId("receive", "sys|A")) } returns false
        val saved = slot<OrgRoleBlockEntity>()
        every { roleBlocks.save(capture(saved)) } returnsArgument 0

        adapter.toggleBlockedRole(InvitationDirection.INBOUND, "sys|A")

        assertEquals(OrgRoleBlockId("receive", "sys|A"), saved.captured.id)
        verify(exactly = 0) { roleBlocks.deleteById(any()) }
    }

    @Test
    fun `toggling a role that is already blocked removes it`() {
        every { roleBlocks.existsById(OrgRoleBlockId("send", "sys|B")) } returns true
        every { roleBlocks.deleteById(OrgRoleBlockId("send", "sys|B")) } just Runs

        adapter.toggleBlockedRole(InvitationDirection.OUTBOUND, "sys|B")

        verify(exactly = 1) { roleBlocks.deleteById(OrgRoleBlockId("send", "sys|B")) }
        verify(exactly = 0) { roleBlocks.save(any()) }
    }

    // --- Blocked users ------------------------------------------------------

    @Test
    fun `lists the stored blocked mxids`() {
        every { blockedUsers.findAll() } returns listOf(
            OrgBlockedUserEntity("@anna:matrix.pgk.example"),
            OrgBlockedUserEntity("@bob:other.example")
        )

        assertEquals(
            setOf(Mxid("@anna:matrix.pgk.example"), Mxid("@bob:other.example")),
            adapter.getBlockedUsers()
        )
    }

    @Test
    fun `reports an mxid as blocked only when a row exists`() {
        every { blockedUsers.existsById("@anna:matrix.pgk.example") } returns true
        every { blockedUsers.existsById("@bob:other.example") } returns false

        assertEquals(true, adapter.isBlocked(Mxid("@anna:matrix.pgk.example")))
        assertEquals(false, adapter.isBlocked(Mxid("@bob:other.example")))
    }

    @Test
    fun `blocking an mxid that is not stored yet saves it`() {
        every { blockedUsers.existsById("@anna:matrix.pgk.example") } returns false
        val saved = slot<OrgBlockedUserEntity>()
        every { blockedUsers.save(capture(saved)) } returnsArgument 0

        adapter.setBlockedUser(Mxid("@anna:matrix.pgk.example"), true)

        assertEquals("@anna:matrix.pgk.example", saved.captured.mxid)
    }

    @Test
    fun `blocking an mxid that is already stored does not duplicate it`() {
        every { blockedUsers.existsById("@anna:matrix.pgk.example") } returns true

        adapter.setBlockedUser(Mxid("@anna:matrix.pgk.example"), true)

        verify(exactly = 0) { blockedUsers.save(any()) }
    }

    @Test
    fun `unblocking an mxid deletes it`() {
        every { blockedUsers.existsById("@anna:matrix.pgk.example") } returns true
        every { blockedUsers.deleteById("@anna:matrix.pgk.example") } just Runs

        adapter.setBlockedUser(Mxid("@anna:matrix.pgk.example"), false)

        verify(exactly = 1) { blockedUsers.deleteById("@anna:matrix.pgk.example") }
    }

    @Test
    fun `unblocking an mxid that is not stored does nothing`() {
        every { blockedUsers.existsById("@anna:matrix.pgk.example") } returns false

        adapter.setBlockedUser(Mxid("@anna:matrix.pgk.example"), false)

        verify(exactly = 0) { blockedUsers.deleteById(any()) }
    }
}
