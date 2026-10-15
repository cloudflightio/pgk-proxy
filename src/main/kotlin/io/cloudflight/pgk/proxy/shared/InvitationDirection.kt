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

package io.cloudflight.pgk.proxy.shared

/**
 * Direction of an invitation, relative to this proxy: receiving invitations into the
 * network ([INBOUND]) or sending invitations to another network ([OUTBOUND]).
 */
enum class InvitationDirection(val direction: String) {
    INBOUND("inbound"),
    OUTBOUND("outbound");

    /**
     * UI/storage term used by the invitation-rights admin page and the `pgk` database
     * (`receive`/`send`). Single source of truth for those magic strings.
     */
    val storageValue: String
        get() = when (this) {
            INBOUND -> "receive"
            OUTBOUND -> "send"
        }

    companion object {
        /**
         * Inverse of [storageValue]; throws for anything but `receive`/`send`.
         */
        fun fromStorageValue(value: String): InvitationDirection = when (value) {
            INBOUND.storageValue -> INBOUND
            OUTBOUND.storageValue -> OUTBOUND
            else -> throw IllegalArgumentException("Unknown invitation direction: $value")
        }
    }
}
