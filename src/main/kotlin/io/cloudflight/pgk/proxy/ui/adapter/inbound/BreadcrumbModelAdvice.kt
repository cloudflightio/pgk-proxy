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

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Derives the header breadcrumb trail for every page from the current request path, so the
 * pages never hard-code their breadcrumbs. Every trail starts with "Verwaltung" followed by
 *  the sidebar section: "Persönlich" for personal pages, "Admin" for the admin pages.
 */
@ControllerAdvice
class BreadcrumbModelAdvice {

    @ModelAttribute("breadcrumbs")
    fun breadcrumbs(request: HttpServletRequest): List<String> =
        breadcrumbsFor(request.requestURI)

    fun breadcrumbsFor(path: String): List<String> = when {
        path == "" || path == "/" || path == "/index.html" ->
            listOf("Verwaltung", "Persönlich", "Rollen")
        path.startsWith("/me/invitation-rules") ->
            listOf("Verwaltung", "Persönlich", "Einladungen")
        path.startsWith("/admin/visibility") ->
            listOf("Verwaltung", "Admin", "Sichtbarkeit")
        path.startsWith("/admin/invitation-rights") ->
            listOf("Verwaltung", "Admin", "Einladungsrechte")
        path.startsWith("/admin/organizations") ->
            listOf("Verwaltung", "Admin", "Organisationen")
        path.startsWith("/admin/practitioner-roles") ->
            listOf("Verwaltung", "Admin", "Rollen zuweisen")
        else -> listOf("Verwaltung")
    }
}
