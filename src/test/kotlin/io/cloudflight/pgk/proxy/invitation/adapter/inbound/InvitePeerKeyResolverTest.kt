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

package io.cloudflight.pgk.proxy.invitation.adapter.inbound

import io.cloudflight.pgk.proxy.invitation.mapper.InvitationRequestMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InvitePeerKeyResolverTest {

    private val resolver = InvitePeerKeyResolver(InvitationRequestMapper())

    @Test
    fun `uses the PDU origin as the peer key`() {
        val body = """{"event": {"origin": "partner.traefik.me", "sender": "@alice:partner.traefik.me"}}"""
        assertEquals("partner.traefik.me", resolver.resolve(body))
    }

    @Test
    fun `falls back to the sender domain when origin is missing`() {
        val body = """{"event": {"sender": "@alice:partner.traefik.me"}}"""
        assertEquals("partner.traefik.me", resolver.resolve(body))
    }

    @Test
    fun `falls back to the shared bucket when the PDU has no peer`() {
        val body = """{"event": {"room_id": "!r:local"}}"""
        assertEquals(InvitePeerKeyResolver.UNKNOWN_PEER, resolver.resolve(body))
    }

    @Test
    fun `falls back to the shared bucket when the PDU is not parseable`() {
        assertEquals(InvitePeerKeyResolver.UNKNOWN_PEER, resolver.resolve("not json"))
    }
}
