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
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA mapping of one organization-wide blocked Matrix user (table `org_blocked_users`).
 *
 * Presence of a row = the mxid is blocked from sending AND receiving federated room
 * invitations into the managed domain; absence = allowed (default allow).
 */
@Entity
@Table(name = "org_blocked_users")
class OrgBlockedUserEntity(
    @Id
    @Column(name = "mxid", nullable = false)
    var mxid: String = ""
)
