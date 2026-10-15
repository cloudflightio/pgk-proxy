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
import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerRoleInfo
import io.cloudflight.pgk.proxy.invitation.domain.rule.InvitationRule
import io.cloudflight.pgk.proxy.invitation.domain.rule.MxidMatcher
import io.cloudflight.pgk.proxy.invitation.domain.rule.RoleMatcher
import io.cloudflight.pgk.proxy.invitation.mapper.InvitationRequestMapper
import io.cloudflight.pgk.proxy.invitation.port.InvitationRulePort
import io.cloudflight.pgk.proxy.invitation.port.RoleLookupPort
import io.cloudflight.pgk.proxy.invitation.port.UserExistsInDirectoryPort
import io.cloudflight.pgk.proxy.shared.Decision
import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import io.cloudflight.pgk.proxy.shared.events.InvitationAllowedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationBlockedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher

class InvitationControlServiceImplTest {

    private val requestMapper: InvitationRequestMapper = mock()
    private val whitelistPort: FederationWhitelistPort = mock()
    private val userExistsPort: UserExistsInDirectoryPort = mock()
    private val blockedUserPort: OrgBlockedUserQueryPort = mock()
    private val blockedRolePort: OrgBlockedRoleQueryPort = mock()
    private val userRightsPort: UserInvitationRightsByMxidQueryPort = mock()
    private val roleLookupPort: RoleLookupPort = mock()
    private val invitationRulePort: InvitationRulePort = mock()
    private val invitationEvaluator = InvitationEvaluator()
    private val applicationEvents: ApplicationEventPublisher = mock()

    private val localDomain = "matrix.pgk.example"
    private val properties = ProxyProperties(federation = ProxyProperties.FederationProperties(domain = "https://$localDomain"))

    private val service = InvitationControlServiceImpl(
        requestMapper, whitelistPort, userExistsPort,
        blockedUserPort, blockedRolePort, userRightsPort, roleLookupPort,
        invitationRulePort, invitationEvaluator, applicationEvents, properties
    )

    // inbound federation invite: remote user invites a user of the local domain
    private val sender = Mxid("@alice:other.example")
    private val recipient = Mxid("@bob:matrix.pgk.example")
    private val request = InvitationRequest(sender, recipient, "room", "event", InvitationDirection.INBOUND)
    private val origin = Fqdn("other.example")

    private val pduJson =
        """{"event":{"origin":"other.example","sender":"@alice:other.example","state_key":"@bob:matrix.pgk.example"}}"""

    // outbound federation invite: a user of the local domain invites a remote user
    private val outboundSender = Mxid("@bob:matrix.pgk.example")
    private val outboundRecipient = Mxid("@alice:other.example")
    private val outboundRequest = InvitationRequest(outboundSender, outboundRecipient, "room", "event", InvitationDirection.OUTBOUND)
    private val outboundOrigin = Fqdn(localDomain)

    private val outboundPduJson =
        """{"event":{"origin":"matrix.pgk.example","sender":"@bob:matrix.pgk.example","state_key":"@alice:other.example"}}"""

    private val receiveBlockedToken = "https://cs|receive-blocked"
    private val sendBlockedToken = "https://cs|send-blocked"

    private fun setUpRequestMocks(originAllowed: Boolean) {
        whenever(requestMapper.fromPdu(pduJson)).thenReturn(request)
        whenever(requestMapper.extractOrigin(pduJson)).thenReturn(origin)
        whenever(whitelistPort.isDomainAllowed(origin, InvitationDirection.INBOUND)).thenReturn(originAllowed)
        whenever(userExistsPort.exists(sender)).thenReturn(true)
        whenever(userExistsPort.exists(recipient)).thenReturn(true)
        whenever(invitationRulePort.getRulesFor(any())).thenReturn(emptyList())
        whenever(blockedUserPort.isBlocked(any())).thenReturn(false)
        whenever(blockedRolePort.isAnyRoleBlocked(any(), any())).thenReturn(false)
        whenever(roleLookupPort.lookupRoles(sender)).thenReturn(listOf(roleOf("default-role")))
        whenever(roleLookupPort.lookupRoles(recipient)).thenReturn(listOf(roleOf("default-role")))
    }

    private fun setUpOutboundRequestMocks(originAllowed: Boolean) {
        whenever(requestMapper.fromPdu(outboundPduJson)).thenReturn(outboundRequest)
        whenever(requestMapper.extractOrigin(outboundPduJson)).thenReturn(outboundOrigin)
        whenever(whitelistPort.isDomainAllowed(Fqdn(outboundRecipient.domain), InvitationDirection.OUTBOUND)).thenReturn(originAllowed)
        whenever(userExistsPort.exists(outboundSender)).thenReturn(true)
        whenever(userExistsPort.exists(outboundRecipient)).thenReturn(true)
        whenever(invitationRulePort.getRulesFor(any())).thenReturn(emptyList())
        whenever(blockedUserPort.isBlocked(any())).thenReturn(false)
        whenever(blockedRolePort.isAnyRoleBlocked(any(), any())).thenReturn(false)
        whenever(roleLookupPort.lookupRoles(outboundSender)).thenReturn(listOf(roleOf("default-role")))
        whenever(roleLookupPort.lookupRoles(outboundRecipient)).thenReturn(listOf(roleOf("default-role")))
    }

    private fun stubBlockedUsers(vararg mxids: Mxid) {
        mxids.forEach { whenever(blockedUserPort.isBlocked(it)).thenReturn(true) }
    }

    private fun stubRights(vararg entries: InvitationRightsEntry) {
        entries.forEach { entry ->
            whenever(userRightsPort.getPractitionerRights(entry.mxid!!)).thenReturn(entry)
        }
    }

    private fun stubBlockedRoles(
        receive: List<String> = emptyList(),
        send: List<String> = emptyList()
    ) {
        whenever(blockedRolePort.isAnyRoleBlocked(any(), any())).thenReturn(false)
        if (receive.isNotEmpty()) {
            whenever(blockedRolePort.isAnyRoleBlocked(receive.toSet(), InvitationDirection.INBOUND)).thenReturn(true)
        }
        if (send.isNotEmpty()) {
            whenever(blockedRolePort.isAnyRoleBlocked(send.toSet(), InvitationDirection.OUTBOUND)).thenReturn(true)
        }
    }

    private fun roleOf(code: String, system: String = "https://cs") =
        PractitionerRoleInfo(code = code, codeSystem = system, trustLevel = TrustLevel.TL_1, organizationReference = null)

    // --- inbound federation invites: federation-wide policy --------------------

    @Test
    @DisplayName("allows the invitation when origin is whitelisted, both peers known and policy is clear")
    fun allowsWhenOriginWhitelistedAndPolicyClear() {
        setUpRequestMocks(originAllowed = true)

        assertEquals(InvitationEvaluation.Allowed, service.evaluateInvitation(pduJson))

        verify(applicationEvents).publishEvent(
            argWhere<InvitationAllowedEvent> { it.sender == sender && it.recipient == recipient && it.origin == origin }
        )
    }

    @Test
    @DisplayName("denies the invitation when the origin server is not whitelisted")
    fun deniesWhenOriginIsNotWhitelisted() {
        setUpRequestMocks(originAllowed = false)

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the inbound invitation when the recipient's domain is neither local nor whitelisted")
    fun deniesInboundWhenRecipientDomainIsForeign() {
        val foreignJson =
            """{"event":{"origin":"other.example","sender":"@alice:other.example","state_key":"@carol:foreign.example"}}"""
        val foreignRequest = InvitationRequest(
            Mxid("@alice:other.example"), Mxid("@carol:foreign.example"), "room", "event", InvitationDirection.INBOUND
        )
        whenever(requestMapper.fromPdu(foreignJson)).thenReturn(foreignRequest)
        whenever(requestMapper.extractOrigin(foreignJson)).thenReturn(origin)
        whenever(whitelistPort.isDomainAllowed(origin, InvitationDirection.INBOUND)).thenReturn(true)
        whenever(whitelistPort.isDomainAllowed(Fqdn("foreign.example"), InvitationDirection.INBOUND)).thenReturn(false)
        whenever(userExistsPort.exists(any())).thenReturn(true)

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(foreignJson))
    }

    @Test
    @DisplayName("denies the invitation when the sender has no Practitioner directory entry")
    fun deniesWhenSenderIsNotInDirectory() {
        setUpRequestMocks(originAllowed = true)
        whenever(userExistsPort.exists(sender)).thenReturn(false)

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the recipient has no Practitioner directory entry")
    fun deniesWhenRecipientIsNotInDirectory() {
        setUpRequestMocks(originAllowed = true)
        whenever(userExistsPort.exists(recipient)).thenReturn(false)

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the sender has no practitioner role")
    fun deniesWhenSenderHasNoPractitionerRole() {
        setUpRequestMocks(originAllowed = true)
        whenever(roleLookupPort.lookupRoles(sender)).thenReturn(emptyList())

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the recipient has no practitioner role")
    fun deniesWhenRecipientHasNoPractitionerRole() {
        setUpRequestMocks(originAllowed = true)
        whenever(roleLookupPort.lookupRoles(recipient)).thenReturn(emptyList())

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(pduJson))
    }

    // --- inbound federation invites: organization-wide policy -----------------

    @Test
    @DisplayName("denies the invitation when the recipient is on the blocked mxid list")
    fun deniesWhenRecipientIsBlocked() {
        setUpRequestMocks(originAllowed = true)
        stubBlockedUsers(recipient)

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_USER), service.evaluateInvitation(pduJson))

        verify(applicationEvents).publishEvent(
            argWhere<InvitationBlockedEvent> { it.sender == sender && it.recipient == recipient && it.origin == origin }
        )
    }

    @Test
    @DisplayName("denies the invitation when the sender is on the blocked mxid list")
    fun deniesWhenSenderIsBlocked() {
        setUpRequestMocks(originAllowed = true)
        stubBlockedUsers(sender)

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_USER), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the recipient's receive right is disabled")
    fun deniesWhenRecipientReceiveIsDisabled() {
        setUpRequestMocks(originAllowed = true)
        stubRights(InvitationRightsEntry("p-1", "Alice", mxid = recipient, receive = false, send = true))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_RIGHTS_DISABLED), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the recipient has a receive-blocked role")
    fun deniesWhenRecipientHasReceiveBlockedRole() {
        setUpRequestMocks(originAllowed = true)
        stubBlockedRoles(receive = listOf(receiveBlockedToken))
        whenever(roleLookupPort.lookupRoles(recipient)).thenReturn(listOf(roleOf("receive-blocked")))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_ROLE), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the sender has a send-blocked role")
    fun deniesWhenSenderHasSendBlockedRole() {
        setUpRequestMocks(originAllowed = true)
        stubBlockedRoles(send = listOf(sendBlockedToken))
        whenever(roleLookupPort.lookupRoles(sender)).thenReturn(listOf(roleOf("send-blocked")))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_ROLE), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the sender's send right is disabled")
    fun deniesWhenSenderSendIsDisabled() {
        setUpRequestMocks(originAllowed = true)
        stubRights(InvitationRightsEntry("p-1", "Bob", mxid = sender, receive = true, send = false))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_RIGHTS_DISABLED), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("allows the invitation when the recipient's roles are not blocked")
    fun allowsWhenRecipientHasOnlyAllowedRoles() {
        setUpRequestMocks(originAllowed = true)
        stubBlockedRoles(receive = listOf(receiveBlockedToken))
        whenever(roleLookupPort.lookupRoles(recipient)).thenReturn(listOf(roleOf("allowed-role")))

        assertEquals(InvitationEvaluation.Allowed, service.evaluateInvitation(pduJson))
    }

    // --- inbound federation invites: personal rules ---------------------------

    @Test
    @DisplayName("denies the invitation when the recipient blacklists the sender's mxid")
    fun deniesWhenRecipientBlacklistsSenderMxid() {
        setUpRequestMocks(originAllowed = true)
        whenever(invitationRulePort.getRulesFor(recipient)).thenReturn(
            listOf(
                InvitationRule.UserPersonal(
                    mxidMatcher = MxidMatcher.Exact(sender),
                    roleMatcher = RoleMatcher.Any,
                    decision = Decision.DENY
                )
            )
        )

        assertEquals(InvitationEvaluation.Denied(DenialReason.PERSONAL_BLACKLIST), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("denies the invitation when the recipient blacklists one of the sender's roles")
    fun deniesWhenRecipientBlacklistsSenderRole() {
        setUpRequestMocks(originAllowed = true)
        whenever(invitationRulePort.getRulesFor(recipient)).thenReturn(
            listOf(
                InvitationRule.UserPersonal(
                    mxidMatcher = MxidMatcher.Any,
                    roleMatcher = RoleMatcher.ByCode(system = "https://roles.example", code = "cardio"),
                    decision = Decision.DENY
                )
            )
        )
        whenever(roleLookupPort.lookupRoles(sender)).thenReturn(
            listOf(
                PractitionerRoleInfo(
                    code = "cardio",
                    codeSystem = "https://roles.example",
                    trustLevel = TrustLevel.TL_2,
                    organizationReference = "Organization/1"
                )
            )
        )

        assertEquals(InvitationEvaluation.Denied(DenialReason.PERSONAL_BLACKLIST), service.evaluateInvitation(pduJson))
    }

    @Test
    @DisplayName("allows the invitation when the blacklist only contains non-matching rules")
    fun allowsWhenBlacklistContainsOnlyNonMatchingRules() {
        setUpRequestMocks(originAllowed = true)
        whenever(invitationRulePort.getRulesFor(recipient)).thenReturn(
            listOf(
                InvitationRule.UserPersonal(
                    mxidMatcher = MxidMatcher.Exact(Mxid("@carol:other.example")),
                    roleMatcher = RoleMatcher.Any,
                    decision = Decision.DENY
                )
            )
        )

        assertEquals(InvitationEvaluation.Allowed, service.evaluateInvitation(pduJson))
    }

    // --- outbound federation invites (local -> remote) -----------------------

    @Test
    @DisplayName("allows the outbound invitation when the policy is clear")
    fun allowsOutboundWhenPolicyClear() {
        setUpOutboundRequestMocks(originAllowed = true)

        assertEquals(InvitationEvaluation.Allowed, service.evaluateInvitation(outboundPduJson))

        verify(applicationEvents).publishEvent(
            argWhere<InvitationAllowedEvent> {
                it.sender == outboundSender && it.recipient == outboundRecipient && it.origin == outboundOrigin
            }
        )
    }

    @Test
    @DisplayName("denies the outbound invitation when the recipient's domain is not on the federation whitelist")
    fun deniesOutboundWhenRecipientDomainIsNotWhitelisted() {
        setUpOutboundRequestMocks(originAllowed = false)

        assertEquals(InvitationEvaluation.Denied(DenialReason.FEDERATION_POLICY), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("denies the outbound invitation when the sender's send right is disabled")
    fun deniesOutboundWhenSenderSendIsDisabled() {
        setUpOutboundRequestMocks(originAllowed = true)
        stubRights(InvitationRightsEntry("p-1", "Bob", mxid = outboundSender, receive = true, send = false))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_RIGHTS_DISABLED), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("denies the outbound invitation when the sender has a send-blocked role")
    fun deniesOutboundWhenSenderHasSendBlockedRole() {
        setUpOutboundRequestMocks(originAllowed = true)
        stubBlockedRoles(send = listOf(sendBlockedToken))
        whenever(roleLookupPort.lookupRoles(outboundSender)).thenReturn(listOf(roleOf("send-blocked")))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_ROLE), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("denies the outbound invitation when the remote recipient has a receive-blocked role")
    fun deniesOutboundWhenRecipientHasReceiveBlockedRole() {
        setUpOutboundRequestMocks(originAllowed = true)
        stubBlockedRoles(receive = listOf(receiveBlockedToken))
        whenever(roleLookupPort.lookupRoles(outboundRecipient)).thenReturn(listOf(roleOf("receive-blocked")))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_BLOCKED_ROLE), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("denies the outbound invitation when the remote recipient's receive right is disabled")
    fun deniesOutboundWhenRecipientReceiveIsDisabled() {
        setUpOutboundRequestMocks(originAllowed = true)
        stubRights(InvitationRightsEntry("p-1", "Alice", mxid = outboundRecipient, receive = false, send = true))

        assertEquals(InvitationEvaluation.Denied(DenialReason.ORG_RIGHTS_DISABLED), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("denies the outbound invitation when the remote recipient blacklists the sender's mxid")
    fun deniesOutboundWhenRecipientBlacklistsSenderMxid() {
        setUpOutboundRequestMocks(originAllowed = true)
        whenever(invitationRulePort.getRulesFor(outboundRecipient)).thenReturn(
            listOf(
                InvitationRule.UserPersonal(
                    mxidMatcher = MxidMatcher.Exact(outboundSender),
                    roleMatcher = RoleMatcher.Any,
                    decision = Decision.DENY
                )
            )
        )

        assertEquals(InvitationEvaluation.Denied(DenialReason.PERSONAL_BLACKLIST), service.evaluateInvitation(outboundPduJson))
    }

    @Test
    @DisplayName("allows the outbound invitation when the sender's roles are not blocked")
    fun allowsOutboundWhenSenderHasOnlyAllowedRoles() {
        setUpOutboundRequestMocks(originAllowed = true)
        stubBlockedRoles(send = listOf(sendBlockedToken))
        whenever(roleLookupPort.lookupRoles(outboundSender)).thenReturn(listOf(roleOf("allowed-role")))

        assertEquals(InvitationEvaluation.Allowed, service.evaluateInvitation(outboundPduJson))
    }
}
