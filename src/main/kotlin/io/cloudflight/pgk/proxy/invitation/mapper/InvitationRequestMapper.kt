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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.invitation.domain.model.InvitationRequest
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.Mxid
import org.springframework.stereotype.Component

@Component
class InvitationRequestMapper {

    private val objectMapper = ObjectMapper()

    fun extractOrigin(pduJson: String): Fqdn {
        val node = objectMapper.readTree(pduJson)
        val event = node.get("event")
        event?.get("origin")?.asText()?.let { return Fqdn(it) }
        val sender = event?.get("sender")?.asText()
            ?: throw IllegalArgumentException("Invite is missing the origin")
        return Fqdn(Mxid(sender).domain)
    }

    fun fromPdu(pduJson: String): InvitationRequest {
        val root = objectMapper.readTree(pduJson)
        val event = root.get("event")
            ?: throw IllegalArgumentException("Invite PDU is missing the 'event' object")

        return InvitationRequest(
            sender = parseMxid(event, "sender", "Invite is missing the sender"),
            recipient = parseMxid(event, "state_key", "Invite is missing the state_key (recipient)"),
            roomId = event.get("room_id")?.asText().orEmpty(),
            eventId = event.get("event_id")?.asText().orEmpty(),
            direction = InvitationDirection.INBOUND
        )
    }

    private fun parseMxid(event: JsonNode, field: String, message: String): Mxid {
        val value = event.get(field)?.asText() ?: throw IllegalArgumentException(message)
        return Mxid(value)
    }
}
