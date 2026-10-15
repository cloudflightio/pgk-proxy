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

package io.cloudflight.pgk.proxy.invitation.application

sealed interface InvitationEvaluation {
    object Allowed : InvitationEvaluation
    data class Denied(val reason: DenialReason) : InvitationEvaluation
}

enum class DenialReason {
    /** Origin not whitelisted or a peer is missing from the directory (federation-wide policy). */
    FEDERATION_POLICY,
    /** A personal blacklist rule (blocked mxid or role) matched the sender. */
    PERSONAL_BLACKLIST,
    /** Sender or recipient is on the organization-wide blocked-mxid list. */
    ORG_BLOCKED_USER,
    /** Recipient has a role that is blocked for receiving invitations. */
    ORG_BLOCKED_ROLE,
    /** Recipient's per-user receive right is disabled. */
    ORG_RIGHTS_DISABLED
}

interface InvitationControlService {

    /**
     * Evaluates a federation invite PDU (`/_matrix/federation/v2/invite`), regardless of
     * direction. Applies the federation whitelist, the directory checks, the organization-wide
     * policy (blocked mxids, blocked send/receive roles, per-user send/receive rights) and
     * finally the recipient's personal blacklist rules. The direction is derived from the PDU
     * origin: a PDU sent from the local domain is an outbound invite (sender enforced), any
     * other origin is inbound (recipient enforced).
     */
    fun evaluateInvitation(pduJson: String): InvitationEvaluation
}
