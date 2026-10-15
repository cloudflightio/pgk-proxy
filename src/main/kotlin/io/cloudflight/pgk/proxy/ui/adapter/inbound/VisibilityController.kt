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

package io.cloudflight.pgk.proxy.ui.adapter.inbound

import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.VisibilityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * Admin page for the directory visibility (Sichtbarkeit).
 *
 * Lists the FHIR practitioners provisioned by the IDP and the healthcare services, each
 * restricted to entries that reference a Matrix mxid. The segmented Öffentlich/Intern control
 * persists the visibility as a FHIR meta security tag; a click swaps the segment in place
 * via HTMX.
 */
@Controller
class VisibilityController(
    private val visibilityService: VisibilityService,
    private val auditLogger: AuditLogger
) {

    @GetMapping("/admin/visibility")
    fun visibility(
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        model.addAttribute("currentUser", CurrentUser.from(principal))
        model.addAttribute("practitioners", visibilityService.listPractitioners())
        model.addAttribute("healthcareProviders", visibilityService.listHealthcareProviders())
        return "visibility"
    }

    /**
     * HTMX persists a visibility change and returns the updated segment in place. On failure
     * the segment is rendered back in its unchanged state together with an error notification.
     */
    @PostMapping("/admin/visibility")
    fun setVisibility(
        @RequestParam id: String,
        @RequestParam visibility: String,
        model: Model
    ): String {
        val requested = parseVisibility(visibility)
        model.addAttribute("entryId", id)
        model.addAttribute("public", requested == Visibility.PUBLIC)
        return try {
            visibilityService.setVisibility(id, requested)
            auditLogger.info(AuditAction.VisibilitySet, "Visibility of '$id' set to ${requested.code}")
            "fragments/visibility :: segment"
        } catch (e: Exception) {
            auditLogger.error(AuditAction.VisibilitySet, "Failed to set visibility of '$id' to ${requested.code}", e)
            model.addAttribute("public", requested != Visibility.PUBLIC)
            model.addAttribute("error", "Änderung fehlgeschlagen")
            "fragments/visibility :: segment-error"
        }
    }

    private fun parseVisibility(value: String): Visibility =
        Visibility.entries.find { it.code == value }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown visibility: $value")
}
