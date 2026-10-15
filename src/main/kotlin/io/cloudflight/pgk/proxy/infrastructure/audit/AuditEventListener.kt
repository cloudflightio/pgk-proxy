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

package io.cloudflight.pgk.proxy.infrastructure.audit

import io.cloudflight.pgk.proxy.shared.events.AdministratorOrganizationResolveFailedEvent
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogEvent
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.events.FederationBlockedEvent
import io.cloudflight.pgk.proxy.shared.events.FederationListRefreshFailedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationAllowedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationBlockedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardFailedEvent
import io.cloudflight.pgk.proxy.shared.events.InvitationForwardedEvent
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetLoadedEvent
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetParseFailedEvent
import io.cloudflight.pgk.proxy.shared.events.LocalValuesetSkippedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationCreateFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationCreatedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationDeleteFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationDeletedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationSearchFailedEvent
import io.cloudflight.pgk.proxy.shared.events.PendingAuditLogs
import io.cloudflight.pgk.proxy.shared.events.OrganizationTreeLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationUpdateFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationUpdatedEvent
import io.cloudflight.pgk.proxy.shared.events.PractitionerListLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.PractitionerOrganizationResolveFailedEvent
import io.cloudflight.pgk.proxy.shared.events.PractitionerRolesLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleAssignmentSaveFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogEmptyEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleCatalogReloadedEvent
import io.cloudflight.pgk.proxy.shared.events.RoleRemovalFailedEvent
import io.cloudflight.pgk.proxy.shared.events.ValuesetFolderScanFailedEvent
import io.cloudflight.pgk.proxy.shared.events.VisibilityLoadFailedEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AuditEventListener(
    private val publisher: ApplicationEventPublisher,
    private val auditLogger: AuditLogger
) : SmartInitializingSingleton {

    private val logger: Logger = LoggerFactory.getLogger(AuditEventListener::class.java)

    override fun afterSingletonsInstantiated() {
        PendingAuditLogs.drain().forEach { log(it.level, it.actor, it.action, it.message, it.timestamp, it.cause) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun flushPendingLogs() {
        PendingAuditLogs.publisher = publisher
        PendingAuditLogs.ready = true
        PendingAuditLogs.drain().forEach { log(it.level, it.actor, it.action, it.message, it.timestamp, it.cause) }
        auditLogger.info("system", AuditAction.SystemReady, "Audit pipeline ready")
    }

    @EventListener
    fun onAuditLogEvent(event: AuditLogEvent) =
        log(event.level, event.actor, event.action, event.message, event.timestamp, event.cause)

    @EventListener
    fun onFederationListRefreshFailed(event: FederationListRefreshFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.FederationWhitelistRefreshFailed,
            "⚠️ Federation whitelist refresh failed, keeping current whitelist",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onFederationBlocked(event: FederationBlockedEvent) =
        log(AuditLogEvent.Level.WARN, SYSTEM, AuditAction.FederationBlocked,
            "⚠️ Federation control: domain ${event.fqdn.value} blocked for ${event.direction.direction} invitations",
            timestamp = event.timestamp)

    @EventListener
    fun onInvitationAllowed(event: InvitationAllowedEvent) =
        log(AuditLogEvent.Level.INFO, event.sender.value, AuditAction.InvitationAllowed,
            "✅ Federation control: invitation allowed sender=${event.sender.value} recipient=${event.recipient.value} origin=${event.origin.value}",
            timestamp = event.timestamp)

    @EventListener
    fun onInvitationBlocked(event: InvitationBlockedEvent) =
        log(AuditLogEvent.Level.WARN, event.sender.value, AuditAction.InvitationBlocked,
            "⚠️ Federation control: invitation blocked sender=${event.sender.value} recipient=${event.recipient.value} origin=${event.origin.value} rule=${event.triggeringRule}",
            timestamp = event.timestamp)

    @EventListener
    fun onInvitationForwarded(event: InvitationForwardedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.InvitationForwarded,
            "✅ Federation control: invite forwarded for room ${event.roomId} event ${event.eventId} -> ${event.target} (${event.statusCode})",
            timestamp = event.timestamp)

    @EventListener
    fun onInvitationForwardFailed(event: InvitationForwardFailedEvent) =
        log(AuditLogEvent.Level.WARN, SYSTEM, AuditAction.InvitationForwardFailed,
            "⚠️ Federation control: failed to forward invite for room ${event.roomId} event ${event.eventId} -> ${event.target}: ${event.cause ?: "unknown"}",
            timestamp = event.timestamp)

    @EventListener
    fun onVisibilityLoadFailed(event: VisibilityLoadFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.VisibilityLoadFailed,
            "⚠️ Failed to load visibility ${event.context}",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onRoleCatalogReloaded(event: RoleCatalogReloadedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.RoleCatalogReloaded,
            "Loaded ${event.entries} role catalog entries from local valuesets",
            timestamp = event.timestamp)

    @EventListener
    fun onRoleCatalogEmpty(event: RoleCatalogEmptyEvent) =
        if (event.keptPreviousSnapshot) {
            log(AuditLogEvent.Level.WARN, SYSTEM, AuditAction.RoleCatalogEmpty,
                "Role catalog reload returned no entries; keeping previous snapshot",
                timestamp = event.timestamp)
        } else {
            log(AuditLogEvent.Level.WARN, SYSTEM, AuditAction.RoleCatalogEmpty,
                "Role catalog returned no entries; will retry on the next lookup",
                timestamp = event.timestamp)
        }

    @EventListener
    fun onLocalValuesetLoaded(event: LocalValuesetLoadedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.ValuesetLoaded,
            "Loaded local valueset '${event.title}' from ${event.filename}",
            timestamp = event.timestamp)

    @EventListener
    fun onLocalValuesetSkipped(event: LocalValuesetSkippedEvent) =
        log(AuditLogEvent.Level.WARN, SYSTEM, AuditAction.ValuesetSkipped,
            "Skipping local valueset ${event.filename}: could not be read",
            timestamp = event.timestamp)

    @EventListener
    fun onValuesetFolderScanFailed(event: ValuesetFolderScanFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.ValuesetScanFailed,
            "Failed to scan valueset folder '${event.folder}'",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onLocalValuesetParseFailed(event: LocalValuesetParseFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.ValuesetParseFailed,
            "Failed to parse local valueset ${event.filename}",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onOrganizationSearchFailed(event: OrganizationSearchFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.OrganizationSearchFailed,
            "⚠️ Organization search failed for query '${event.query}'",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onOrganizationCreated(event: OrganizationCreatedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.OrganizationCreated,
            "✅ Created organization ${event.organizationId} under ${event.parentId}",
            timestamp = event.timestamp)

    @EventListener
    fun onOrganizationCreateFailed(event: OrganizationCreateFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.OrganizationCreateFailed,
            "⚠️ Failed to create organization",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onOrganizationUpdated(event: OrganizationUpdatedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.OrganizationUpdated,
            "✅ Updated organization ${event.organizationId}",
            timestamp = event.timestamp)

    @EventListener
    fun onOrganizationUpdateFailed(event: OrganizationUpdateFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.OrganizationUpdateFailed,
            "⚠️ Failed to update organization ${event.organizationId}",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onOrganizationDeleted(event: OrganizationDeletedEvent) =
        log(AuditLogEvent.Level.INFO, SYSTEM, AuditAction.OrganizationDeleted,
            "✅ Deleted organization ${event.organizationId}",
            timestamp = event.timestamp)

    @EventListener
    fun onOrganizationDeleteFailed(event: OrganizationDeleteFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.OrganizationDeleteFailed,
            "⚠️ Failed to delete organization ${event.organizationId}",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onOrganizationTreeLoadFailed(event: OrganizationTreeLoadFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.OrganizationTreeLoadFailed,
            "⚠️ Failed to load the organization tree",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onRoleAssignmentSaveFailed(event: RoleAssignmentSaveFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.RoleAssignmentSaveFailed,
            "⚠️ Failed to save role assignment for practitioner '${event.practitionerId}'",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onRoleRemovalFailed(event: RoleRemovalFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.RoleRemovalFailed,
            "⚠️ Failed to remove role '${event.roleToken}' from practitioner '${event.practitionerId}'",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onAdministratorOrganizationResolveFailed(event: AdministratorOrganizationResolveFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.AdministratorOrganizationResolveFailed,
            "⚠️ Failed to resolve the administrator's organization",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onPractitionerListLoadFailed(event: PractitionerListLoadFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.PractitionerListLoadFailed,
            "⚠️ Failed to load practitioners for the role assignment page",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onPractitionerOrganizationResolveFailed(event: PractitionerOrganizationResolveFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.PractitionerOrganizationResolveFailed,
            "⚠️ Failed to resolve the practitioner's organization for '${event.practitionerId}'",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onRoleCatalogLoadFailed(event: RoleCatalogLoadFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.RoleCatalogLoadFailed,
            "⚠️ Failed to load the role catalog",
            timestamp = event.timestamp, cause = event.cause)

    @EventListener
    fun onPractitionerRolesLoadFailed(event: PractitionerRolesLoadFailedEvent) =
        log(AuditLogEvent.Level.ERROR, SYSTEM, AuditAction.PractitionerRolesLoadFailed,
            "⚠️ Failed to load existing roles for practitioner '${event.practitionerId}'",
            timestamp = event.timestamp, cause = event.cause)

    private fun log(
        level: AuditLogEvent.Level,
        actor: String,
        action: AuditAction,
        message: String,
        timestamp: Instant = Instant.now(),
        cause: Throwable? = null
    ) {
        val rendered = format(timestamp, actor, action, message)
        when (level) {
            AuditLogEvent.Level.DEBUG -> if (cause == null) logger.debug(rendered) else logger.debug(rendered, cause)
            AuditLogEvent.Level.INFO -> if (cause == null) logger.info(rendered) else logger.info(rendered, cause)
            AuditLogEvent.Level.WARN -> if (cause == null) logger.warn(rendered) else logger.warn(rendered, cause)
            AuditLogEvent.Level.ERROR -> if (cause == null) logger.error(rendered) else logger.error(rendered, cause)
        }
    }

    private fun format(timestamp: Instant, actor: String, action: AuditAction, message: String): String =
        "timestamp=$timestamp actor=$actor action=${action.value} $message"

    private companion object {
        const val SYSTEM = "system"
    }
}
