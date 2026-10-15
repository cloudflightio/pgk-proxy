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

import io.cloudflight.pgk.proxy.search.api.domain.model.PractitionerOption
import io.cloudflight.pgk.proxy.search.api.domain.model.RoleOption
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.BlockedUserRow
import io.cloudflight.pgk.proxy.ui.application.PersonalInvitationRuleView
import io.cloudflight.pgk.proxy.ui.application.PersonalInvitationRulesService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.ViewResolver
import org.springframework.web.servlet.view.AbstractView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant

class PersonalInvitationRulesControllerTest {

    private val service = org.mockito.Mockito.mock(PersonalInvitationRulesService::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(PersonalInvitationRulesController(service, AuditLogger()))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setViewResolvers(noOpViewResolver())
            .build()
        SecurityContextHolder.clearContext()
    }

    private fun noOpViewResolver(): ViewResolver = ViewResolver { _, _ ->
        object : AbstractView() {
            override fun renderMergedOutputModel(
                model: MutableMap<String, Any>,
                request: HttpServletRequest,
                response: HttpServletResponse
            ) {
                response.contentType = "text/html"
            }
        }
    }

    private fun authenticateAs(subject: String) {
        val oidcUser: OidcUser = DefaultOidcUser(
            emptyList(),
            OidcIdToken(
                "token", Instant.now(), Instant.now().plusSeconds(3600),
                mapOf("sub" to subject, "preferred_username" to "max")
            ),
            OidcUserInfo(mapOf("sub" to subject))
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(oidcUser, "n/a", emptyList())
    }

    private val cardiologyRule = PersonalInvitationRuleView(
        organization = "",
        organizationDisplay = "",
        roleToken = "sys|HAA",
        roleDisplay = "Fachärztin/Facharzt für Herzchirurgie"
    )

    // --- Page ----------------------------------------------------------------

    @Test
    fun `page renders with empty rules for an unknown user`() {
        whenever(service.currentUserMxid("user-1")).thenReturn(null)

        mockMvc.perform(get("/me/invitation-rules"))
            .andExpect(status().isOk)
            .andExpect(view().name("personal-invitation-rules"))
            .andExpect(model().attribute<List<Any>>("rules", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()
    }

    @Test
    fun `page renders the flat list of rules`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listRules(Mxid("@alice:other.example"))).thenReturn(listOf(cardiologyRule))
        whenever(service.listBlockedUsers(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(get("/me/invitation-rules"))
            .andExpect(status().isOk)
            .andExpect(view().name("personal-invitation-rules"))
            .andExpect(model().attribute("rules", listOf(cardiologyRule)))
            .andExpect(model().attribute("ownerMxid", "@alice:other.example"))
            .andReturn()
    }

    @Test
    fun `page renders the personal blocked users`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listRules(Mxid("@alice:other.example"))).thenReturn(emptyList())
        whenever(service.listBlockedUsers(Mxid("@alice:other.example"))).thenReturn(
            listOf(BlockedUserRow("@harasser:evil.example", "@harasser:evil.example"))
        )

        mockMvc.perform(get("/me/invitation-rules"))
            .andExpect(status().isOk)
            .andExpect(model().attribute(
                "blockedUsers",
                listOf(BlockedUserRow("@harasser:evil.example", "@harasser:evil.example"))
            ))
            .andReturn()
    }

    // --- Dialog --------------------------------------------------------------

    @Test
    fun `dialog returns a fresh, empty dialog`() {
        mockMvc.perform(get("/me/invitation-rules/dialog"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rules-dialog :: dialog"))
            .andExpect(model().attribute("dialogTitle", "Regel hinzufügen"))
            .andExpect(model().attribute<List<Any>>("roles", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()
    }

    // --- Search --------------------------------------------------------------

    @Test
    fun `search-roles returns roles with the checked state`() {
        whenever(service.searchRoles("facharzt", setOf("sys|HAA"))).thenReturn(
            listOf(RoleOption("sys|HAA", "Fachärztin/Facharzt für Herzchirurgie", "", true))
        )

        mockMvc.perform(get("/me/invitation-rules/search-roles").param("q", "facharzt").param("blockedRoles", "sys|HAA"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rule-search :: roles"))
            .andExpect(model().attribute<List<Any>>("roles", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(service).searchRoles("facharzt", setOf("sys|HAA"))
    }

    @Test
    fun `search-roles is empty for a blank query`() {
        mockMvc.perform(get("/me/invitation-rules/search-roles").param("q", "  "))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rule-search :: roles"))
            .andExpect(model().attribute<List<Any>>("roles", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()

        org.mockito.kotlin.verify(service, org.mockito.kotlin.never()).searchRoles(any(), any())
    }

    // --- Add -----------------------------------------------------------------

    @Test
    fun `add persists the checked roles and returns save-ok`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listRules(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(
            post("/me/invitation-rules")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("blockedRoles", "sys|HAA")
                .param("blockedRoles", "sys|HYG")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rules :: save-ok"))
            .andReturn()

        verify(service).addRules(
            Mxid("@alice:other.example"),
            listOf("sys|HAA", "sys|HYG")
        )
    }

    @Test
    fun `add without a resolvable owner returns the inline error`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(null)

        mockMvc.perform(
            post("/me/invitation-rules")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("blockedRoles", "sys|HAA")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rules :: save-error"))
            .andReturn()
    }

    // --- Remove --------------------------------------------------------------

    @Test
    fun `remove deletes the rule and returns the refreshed panel`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listRules(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(
            post("/me/invitation-rules/remove")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("roleToken", "sys|HAA")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rules :: panel"))
            .andReturn()

        verify(service).removeRule(Mxid("@alice:other.example"), "sys|HAA")
    }

    @Test
    fun `remove failure returns the panel with re-queried rules`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        doThrow(RuntimeException("db down"))
            .whenever(service).removeRule(Mxid("@alice:other.example"), "sys|HAA")
        whenever(service.listRules(Mxid("@alice:other.example"))).thenReturn(listOf(cardiologyRule))

        mockMvc.perform(
            post("/me/invitation-rules/remove")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("roleToken", "sys|HAA")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-rules :: panel"))
            .andExpect(model().attribute("rules", listOf(cardiologyRule)))
            .andReturn()

        verify(service).listRules(Mxid("@alice:other.example"))
    }

    // --- Blocked-user search -------------------------------------------------

    @Test
    fun `search-users returns matching users as suggestions`() {
        whenever(service.searchUsers("anna")).thenReturn(
            listOf(PractitionerOption("Practitioner/1", "Anna Berger", "TL_2", "@anna:matrix.example"))
        )

        mockMvc.perform(get("/me/invitation-rules/search-users").param("mxid", "anna"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-blocked-users :: suggestions"))
            .andExpect(model().attribute("q", "anna"))
            .andExpect(model().attribute<List<Any>>("items", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(service).searchUsers("anna")
    }

    @Test
    fun `search-users is empty for a blank query`() {
        mockMvc.perform(get("/me/invitation-rules/search-users").param("mxid", "  "))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-blocked-users :: suggestions"))
            .andExpect(model().attribute<List<Any>>("items", org.hamcrest.Matchers.hasSize(0)))
            .andReturn()

        org.mockito.kotlin.verify(service, org.mockito.kotlin.never()).searchUsers(any())
    }

    // --- Blocked-user add / remove -------------------------------------------

    @Test
    fun `add blocks the user and returns the refreshed panel`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listBlockedUsers(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(
            post("/me/invitation-rules/users/add")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("mxid", "@harasser:evil.example")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-blocked-users :: panel"))
            .andReturn()

        verify(service).blockUser(Mxid("@alice:other.example"), "@harasser:evil.example")
    }

    @Test
    fun `add with an invalid mxid returns the panel with an inline error`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        doThrow(IllegalArgumentException("invalid mxid"))
            .whenever(service).blockUser(Mxid("@alice:other.example"), "not-an-mxid")
        whenever(service.listBlockedUsers(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(
            post("/me/invitation-rules/users/add")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("mxid", "not-an-mxid")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-blocked-users :: panel"))
            .andExpect(model().attribute("error", "Ungültige MXID: not-an-mxid"))
            .andReturn()
    }

    @Test
    fun `remove unblocks the user and returns the refreshed panel`() {
        authenticateAs("user-1")
        whenever(service.currentUserMxid("user-1")).thenReturn(Mxid("@alice:other.example"))
        whenever(service.listBlockedUsers(Mxid("@alice:other.example"))).thenReturn(emptyList())

        mockMvc.perform(
            post("/me/invitation-rules/users/remove")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("mxid", "@harasser:evil.example")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/personal-blocked-users :: panel"))
            .andReturn()

        verify(service).unblockUser(Mxid("@alice:other.example"), "@harasser:evil.example")
    }
}
