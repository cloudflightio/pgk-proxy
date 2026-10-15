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

package io.cloudflight.pgk.proxy.invitation.mapper

import io.cloudflight.pgk.proxy.shared.InvitationDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvitationRequestMapperTest {

    private val mapper = InvitationRequestMapper()

    private val pdu = """
        {
          "event": {
            "content": { "membership": "invite" },
            "origin": "matrix.pgk.example",
            "origin_server_ts": 1234567890,
            "sender": "@bob:matrix.pgk.example",
            "state_key": "@alice:other.example",
            "room_id": "!room:matrix.pgk.example",
            "event_id": "${'$'}evt-1",
            "type": "m.room.member"
          },
          "room_version": "2"
        }
    """.trimIndent()

    @Test
    @DisplayName("extracts the origin server from the invite event")
    fun extractsOriginFromInvite() {
        assertEquals("matrix.pgk.example", mapper.extractOrigin(pdu).value)
    }

    @Test
    @DisplayName("maps an invite PDU to an inbound invitation request")
    fun mapsInvitePduToRequest() {
        val request = mapper.fromPdu(pdu)

        assertEquals("@bob:matrix.pgk.example", request.sender.value)
        assertEquals("@alice:other.example", request.recipient.value)
        assertEquals("!room:matrix.pgk.example", request.roomId)
        assertEquals("\$evt-1", request.eventId)
        assertEquals(InvitationDirection.INBOUND, request.direction)
    }

    @Test
    @DisplayName("derives the origin from the sender domain when no origin is present")
    fun derivesOriginFromSenderDomain() {
        val pdu = """
            {
              "event": {
                "content": { "membership": "invite" },
                "sender": "@177a5eaa-9434-4ba8-aec7-328d0444bed0:partner.traefik.me",
                "state_key": "@cb5915dc-4124-4bd8-a217-03465c545359:matrix.traefik.me",
                "room_id": "!room:partner.traefik.me"
              }
            }
        """.trimIndent()
        assertEquals("partner.traefik.me", mapper.extractOrigin(pdu).value)
    }

    @Test
    @DisplayName("throws when the invite event has no origin")
    fun throwsWhenInvitePduHasNoOrigin() {
        assertThrows<IllegalArgumentException> {
            mapper.extractOrigin("""{"event":{"type":"m.room.member"}}""")
        }
    }

    @Test
    @DisplayName("throws when the invite PDU has no event")
    fun throwsWhenInvitePduHasNoEvent() {
        assertThrows<IllegalArgumentException> {
            mapper.fromPdu("""{"event":{}}""")
        }
    }

    @Test
    @DisplayName("throws when the invite PDU has no sender")
    fun throwsWhenInvitePduHasNoSender() {
        assertThrows<IllegalArgumentException> {
            mapper.fromPdu("""{"event":{"state_key":"@alice:other.example"}}""")
        }
    }
}
