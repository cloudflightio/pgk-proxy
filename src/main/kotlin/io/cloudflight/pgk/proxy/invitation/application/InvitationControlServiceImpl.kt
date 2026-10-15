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

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.federation.port.FederationWhitelistPort
import io.cloudflight.pgk.proxy.invitation.api.model.InvitationRightsEntry
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRoleQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUserQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsByMxidQueryPort
import io.cloudflight.pgk.proxy.invitation.domain.InvitationEvaluator
import io.cloudflight.pgk.proxy.invitation.domain.model.InvitationRequest
import io.cloudflight.pgk.proxy.invitation.mapper.InvitationRequestMapper
import io.cloudflight.pgk.proxy.invitation.port.InvitationRulePort
import io.cloudflight.pgk.proxy.invitation.port.RoleLookupPort
import io.cloudflight.pgk.proxy.invitation.port.UserExistsInDirectoryPort
import io.cloudflight.pgk.proxy.shared.Decision
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.InvitationAllowedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationBlockedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Decides whether a Matrix room invitation is allowed by the PGK policy. Enforced exclusively at
 * the federation boundary, so invitations between users of the same domain are never evaluated.
 *
 * The federation-wide and organization-wide policy is applied on top of the whitelist/directory
 * checks, followed by the recipient's personal blacklist rules:
 * - Blocked mxids (`org_blocked_users`) may neither send nor receive invitations.
 * - Role blocks (`org_role_invitation_blocks`) block senders with a send-blocked role and
 *   recipients with a receive-blocked role.
 * - Per-practitioner rights (`practitioner_invitation_rights`) disable sending or receiving
 *   for an individual user; absent rows default to allowed.
 * - Personal rules (`personal_invitation_blocks`) blacklist a sender mxid or role for the
 *   recipient.
 *
 * A role matches a blocked token when the FHIR coding's `{system}|{code}` equals the token.
 * Every decision publishes an audit event: [InvitationAllowedEvent] when the invitation is
 * allowed, [InvitationBlockedEvent] when it is denied.
 */
@Service
class InvitationControlServiceImpl(
    private val requestMapper: InvitationRequestMapper,
    private val federationWhitelistPort: FederationWhitelistPort,
    private val userExistsInDirectoryPort: UserExistsInDirectoryPort,
    private val blockedUserPort: OrgBlockedUserQueryPort,
    private val blockedRolePort: OrgBlockedRoleQueryPort,
    private val userRightsPort: UserInvitationRightsByMxidQueryPort,
    private val roleLookupPort: RoleLookupPort,
    private val invitationRulePort: InvitationRulePort,
    private val invitationEvaluator: InvitationEvaluator,
    private val applicationEvents: ApplicationEventPublisher,
    private val properties: ProxyProperties
) : InvitationControlService {

    override fun evaluateInvitation(pduJson: String): InvitationEvaluation {
        val origin = requestMapper.extractOrigin(pduJson)
        val request = requestMapper.fromPdu(pduJson).copy(direction = determineDirection(origin))

        federationPolicyViolation(request, origin)?.let { return it }
        practitionerRoleViolation(request, origin)?.let { return it }
        organizationPolicyViolation(request, origin)?.let { return it }
        personalBlacklistViolation(request, origin)?.let { return it }

        applicationEvents.publishEvent(
            InvitationAllowedEvent(sender = request.sender, recipient = request.recipient, origin = origin)
        )
        return InvitationEvaluation.Allowed
    }

    private fun determineDirection(origin: Fqdn): InvitationDirection =
        if (origin.value == properties.federation.matrixDomain) InvitationDirection.OUTBOUND else InvitationDirection.INBOUND

    /**
     * Federation-wide policy: the origin and the forward destination must be the local federation
     * domain or a whitelisted peer, and both participants must be known to the directory.
     */
    private fun federationPolicyViolation(request: InvitationRequest, origin: Fqdn): InvitationEvaluation? {
        val direction = request.direction
        val originAllowed = when (direction) {
            InvitationDirection.INBOUND -> federationWhitelistPort.isDomainAllowed(origin, direction)
            InvitationDirection.OUTBOUND -> true
        }
        val destinationAllowed = when (direction) {
            InvitationDirection.INBOUND ->
                isCurrentDomain(request) ||
                    federationWhitelistPort.isDomainAllowed(Fqdn(request.recipient.domain), InvitationDirection.INBOUND)
            InvitationDirection.OUTBOUND ->
                federationWhitelistPort.isDomainAllowed(Fqdn(request.recipient.domain), InvitationDirection.OUTBOUND)
        }
        val senderKnown = userExistsInDirectoryPort.exists(request.sender)
        val recipientKnown = userExistsInDirectoryPort.exists(request.recipient)

        return if (!originAllowed || !destinationAllowed || !senderKnown || !recipientKnown) {
            InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY)
        } else {
            null
        }
    }

    private fun practitionerRoleViolation(request: InvitationRequest, origin: Fqdn): InvitationEvaluation? =
        if (!hasAnyRole(request.sender) || !hasAnyRole(request.recipient)) {
            deny(request.sender, request.recipient, origin, "user has no practitioner role", DenialReason.FEDERATION_POLICY)
        } else {
            null
        }

    /**
     * Organization-wide policy: blocked mxids, blocked send/receive roles and per-user send/receive rights.
     */
    private fun organizationPolicyViolation(request: InvitationRequest, origin: Fqdn): InvitationEvaluation? {
        if (blockedUserPort.isBlocked(request.sender) || blockedUserPort.isBlocked(request.recipient)) {
            return deny(request.sender, request.recipient, origin, "blocked mxid", DenialReason.ORG_BLOCKED_USER)
        }
        val senderRights = rightsFor(request.sender)
        if (senderRights?.send == false) {
            return deny(request.sender, request.recipient, origin, "sender send right disabled", DenialReason.ORG_RIGHTS_DISABLED)
        }
        if (hasBlockedRole(request.sender, InvitationDirection.OUTBOUND)) {
            return deny(request.sender, request.recipient, origin, "sender role blocked", DenialReason.ORG_BLOCKED_ROLE)
        }
        val recipientRights = rightsFor(request.recipient)
        if (recipientRights?.receive == false) {
            return deny(request.sender, request.recipient, origin, "recipient receive right disabled", DenialReason.ORG_RIGHTS_DISABLED)
        }
        if (hasBlockedRole(request.recipient, InvitationDirection.INBOUND)) {
            return deny(request.sender, request.recipient, origin, "recipient role blocked", DenialReason.ORG_BLOCKED_ROLE)
        }
        return null
    }

    private fun personalBlacklistViolation(request: InvitationRequest, origin: Fqdn): InvitationEvaluation? {
        val personalRules = invitationRulePort.getRulesFor(request.recipient)
        if (personalRules.isEmpty()) return null
        val senderRoles = roleLookupPort.lookupRoles(request.sender)
        return if (invitationEvaluator.evaluate(request, personalRules, senderRoles, emptyList()) == Decision.DENY) {
            deny(request.sender, request.recipient, origin, "personal blacklist rule matched", DenialReason.PERSONAL_BLACKLIST)
        } else {
            null
        }
    }

    private fun isCurrentDomain(request: InvitationRequest): Boolean =
        request.recipient.domain == properties.federation.matrixDomain

    private fun rightsFor(mxid: Mxid): InvitationRightsEntry? =
        userRightsPort.getPractitionerRights(mxid)

    private fun hasAnyRole(mxid: Mxid): Boolean = roleLookupPort.lookupRoles(mxid).isNotEmpty()

    private fun hasBlockedRole(mxid: Mxid, direction: InvitationDirection): Boolean {
        val userRoles = roleLookupPort.lookupRoles(mxid).map { "${it.codeSystem}|${it.code}" }.toSet()
        if (userRoles.isEmpty()) return false
        return blockedRolePort.isAnyRoleBlocked(userRoles, direction)
    }

    private fun deny(sender: Mxid, recipient: Mxid, origin: Fqdn, rule: String, reason: DenialReason): InvitationEvaluation {
        applicationEvents.publishEvent(InvitationBlockedEvent(sender, recipient, origin, rule))
        return InvitationEvaluation.Denied(reason)
    }
}
