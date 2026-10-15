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

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table

/**
 * JPA mapping of one personal blocked Matrix user (table `personal_blocked_users`).
 *
 * Presence of a row = the mxid [PersonalBlockedUserId.blockedMxid] is blocked from sending
 * this owner ([PersonalBlockedUserId.ownerMxid]) federated room invitations; absence = allowed.
 */
@Entity
@Table(name = "personal_blocked_users")
class PersonalBlockedUserEntity(
    @EmbeddedId
    var id: PersonalBlockedUserId
)

@Embeddable
data class PersonalBlockedUserId(
    @Column(name = "owner_mxid", nullable = false)
    var ownerMxid: String = "",
    @Column(name = "blocked_mxid", nullable = false)
    var blockedMxid: String = ""
)
