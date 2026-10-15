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

package io.cloudflight.pgk.proxy.ui.application

import io.cloudflight.pgk.proxy.config.SecurityConfig
import org.springframework.security.oauth2.core.oidc.user.OidcUser

data class CurrentUser(
    val name: String,
    val initials: String,
    val subject: String = "",
    val admin: Boolean = false
) {
    companion object {
        fun from(principal: OidcUser?): CurrentUser =
            if (principal == null) {
                CurrentUser("Unbekannt", "?")
            } else {
                val given = principal.givenName
                val family = principal.familyName
                CurrentUser(
                    name = listOfNotNull(given, family)
                        .joinToString(" ")
                        .ifBlank { principal.preferredUsername ?: "Unbekannt" },
                    initials = listOfNotNull(given, family)
                        .filter { it.isNotBlank() }
                        .mapNotNull { it.firstOrNull() }
                        .joinToString("")
                        .uppercase()
                        .ifBlank { principal.preferredUsername?.take(2)?.uppercase() ?: "?" },
                    subject = principal.subject!!,
                    admin = principal.authorities.any { it.authority == "ROLE_${SecurityConfig.ADMIN_ROLE}" }
                )
            }
    }
}
