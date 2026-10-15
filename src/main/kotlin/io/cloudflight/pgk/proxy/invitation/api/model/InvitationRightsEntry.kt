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

package io.cloudflight.pgk.proxy.invitation.api.model

import io.cloudflight.pgk.proxy.shared.Mxid

/**
 * A practitioner entry on the invitation-rights admin page, as sourced from the FHIR
 * provider directory. Name is taken as-is; the mxid (when present) backs the subtitle.
 *
 * [receive] and [send] are the persisted invitation rights from the `pgk` database
 * (`practitioner_invitation_rights`). Both default to allowed.
 */
data class InvitationRightsEntry(
    val id: String,
    val name: String,
    val mxid: Mxid? = null,
    val receive: Boolean = true,
    val send: Boolean = true
)
