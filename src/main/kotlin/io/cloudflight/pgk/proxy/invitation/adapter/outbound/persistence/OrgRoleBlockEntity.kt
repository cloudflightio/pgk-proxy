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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence

import jakarta.persistence.*

/**
 * JPA mapping of one blocked role for one direction (table `org_role_invitation_blocks`).
 *
 * Presence of a row = the role is blocked in that direction; an empty table means
 * nothing is blocked. [direction] uses the UI terms `receive`/`send`; [roleToken] is the
 * role catalog token.
 */
@Entity
@Table(name = "org_role_invitation_blocks")
class OrgRoleBlockEntity(
    @EmbeddedId
    var id: OrgRoleBlockId
)

@Embeddable
data class OrgRoleBlockId(
    @Column(name = "direction", length = 10, nullable = false)
    var direction: String = "",
    @Column(name = "role_token", nullable = false)
    var roleToken: String = ""
)
