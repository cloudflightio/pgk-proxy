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

package io.cloudflight.pgk.proxy.shared.events

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

@Component
class AuditLogger {

    fun debug(action: AuditAction, message: String) =
        log(AuditLogEvent.Level.DEBUG, currentActor(), action, message)

    fun info(action: AuditAction, message: String) =
        log(AuditLogEvent.Level.INFO, currentActor(), action, message)

    fun warn(action: AuditAction, message: String) =
        log(AuditLogEvent.Level.WARN, currentActor(), action, message)

    fun error(action: AuditAction, message: String, cause: Throwable? = null) =
        log(AuditLogEvent.Level.ERROR, currentActor(), action, message, cause)

    fun debug(actor: String, action: AuditAction, message: String) =
        log(AuditLogEvent.Level.DEBUG, actor, action, message)

    fun info(actor: String, action: AuditAction, message: String) =
        log(AuditLogEvent.Level.INFO, actor, action, message)

    fun warn(actor: String, action: AuditAction, message: String) =
        log(AuditLogEvent.Level.WARN, actor, action, message)

    fun error(actor: String, action: AuditAction, message: String, cause: Throwable? = null) =
        log(AuditLogEvent.Level.ERROR, actor, action, message, cause)

    private fun log(
        level: AuditLogEvent.Level,
        actor: String,
        action: AuditAction,
        message: String,
        cause: Throwable? = null
    ) {
        val entry = AuditLogEvent(level, actor, action, message, cause = cause)
        if (PendingAuditLogs.ready) {
            PendingAuditLogs.publisher?.publishEvent(entry)
        } else {
            PendingAuditLogs.add(entry)
        }
    }

    private fun currentActor(): String =
        (SecurityContextHolder.getContext().authentication?.principal as? OidcUser)?.subject ?: "system"
}
