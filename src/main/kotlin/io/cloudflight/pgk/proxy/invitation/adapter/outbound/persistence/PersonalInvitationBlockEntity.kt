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
 * JPA mapping of one personal invitation blacklist rule (table `personal_invitation_blocks`).
 *
 * Absence of any row for an owner means all-allowed. A row blocks [PersonalInvitationBlockId.roleToken]
 * attached to [PersonalInvitationBlockId.organization]; the display columns cache names for the UI.
 */
@Entity
@Table(name = "personal_invitation_blocks")
class PersonalInvitationBlockEntity(
    @EmbeddedId
    var id: PersonalInvitationBlockId,
    @Column(name = "organization_display", nullable = false)
    var organizationDisplay: String = "",
    @Column(name = "role_display", nullable = false)
    var roleDisplay: String = ""
)

@Embeddable
data class PersonalInvitationBlockId(
    @Column(name = "owner_mxid", nullable = false)
    var ownerMxid: String = "",
    @Column(name = "organization", nullable = false)
    var organization: String = "",
    @Column(name = "role_token", nullable = false)
    var roleToken: String = ""
)
