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

package io.cloudflight.pgk.proxy.infrastructure.routing

import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.events.WhitelistFetchedEvent
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/**
 * Reacts to [WhitelistFetchedEvent] by writing the remote federation endpoints that are allowed
 * through the egress (Squid) proxy to a plain-text whitelist file.
 *
 * The file is written atomically via a temporary file and `Files.move` so that Squid never observes a
 * partially-written whitelist during its periodic reconfigure.
 */
@Component
class WhitelistFetchedEventListener(
    @Value("\${pgk.squid.whitelist-path:./config/squid/whitelist/whitelist.txt}")
    private val whitelistPath: String,
    private val auditLogger: AuditLogger
) {

    @EventListener
    fun onWhitelistFetched(event: WhitelistFetchedEvent) {
        val file = Path.of(whitelistPath)
        val temp = Path.of("$whitelistPath.tmp")
        val fqdns = event.fqdns.map { it.value }.plus("matrix")

        try {
            file.parent?.let { Files.createDirectories(it) }
            temp.writeText(fqdns.joinToString("\n"))
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            auditLogger.info(
                "system", AuditAction.SquidWhitelistUpdate,
                "Squid whitelist updated: ${event.fqdns.size} domains written to $whitelistPath"
            )
        } catch (e: Exception) {
            auditLogger.error(
                "system", AuditAction.SquidWhitelistUpdateFailed,
                "Failed to write Squid whitelist to $whitelistPath: ${e.message}", e
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}