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
import org.springframework.stereotype.Component

/**
 * Resolves the rate-limit bucket key for an incoming federation invite: the sending server's
 * [origin][io.cloudflight.pgk.proxy.invitation.mapper.InvitationRequestMapper.extractOrigin],
 * so each federating peer gets its own bucket. Malformed PDUs share the [UNKNOWN_PEER] bucket.
 */
@Component
class InvitePeerKeyResolver(
    private val requestMapper: InvitationRequestMapper
) {

    fun resolve(body: String): String =
        runCatching { requestMapper.extractOrigin(body).value }
            .getOrDefault(UNKNOWN_PEER)

    companion object {
        const val UNKNOWN_PEER = "unknown"
    }
}
