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

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Disables the legacy federation invite endpoint (`/_matrix/federation/v1/invite`). Invitations
 * are only accepted via `/_matrix/federation/v2/invite` (see [InvitationController]); the v1
 * route is permanently retired and always answers with 403 so no federated invite can reach
 * Synapse through it.
 */
@RestController
@RequestMapping("/_matrix/federation/v1")
class LegacyInviteController {

    @PutMapping("/invite/{roomId}/{eventId}")
    fun disabledInvitation(
        @PathVariable roomId: String,
        @PathVariable eventId: String,
    ): ResponseEntity<String> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"errcode": "M_FORBIDDEN", "error": "Legacy federation invite endpoint (v1) is disabled."}""")
}
