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

package io.cloudflight.pgk.proxy.invitation.api.port

import io.cloudflight.pgk.proxy.shared.Mxid

/**
 * Change side of the organization-wide user block list: block or unblock a single mxid.
 * Blocked users may neither send nor receive federated room invitations.
 */
interface OrgBlockedUsersChangePort {
    fun setBlockedUser(mxid: Mxid, blocked: Boolean)
}
