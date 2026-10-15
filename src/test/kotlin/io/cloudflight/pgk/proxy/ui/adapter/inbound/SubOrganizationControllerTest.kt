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

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationData
import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.cloudflight.pgk.proxy.search.api.domain.model.OrganizationOption
import io.cloudflight.pgk.proxy.ui.application.OrganizationTreeNode
import io.cloudflight.pgk.proxy.ui.application.SubOrganizationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.view
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.ViewResolver
import org.springframework.web.servlet.view.AbstractView
import java.time.Instant

class SubOrganizationControllerTest {

    private val service = mock(SubOrganizationService::class.java)
    private val eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher::class.java)
    private lateinit var mockMvc: MockMvc

    private val typeOptions = listOf(
        OrganizationType("abteilung", "Abteilung"),
        OrganizationType("institut", "Institut"),
        OrganizationType("ambulanz", "Ambulanz"),
        OrganizationType("station", "Station")
    )

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(SubOrganizationController(service, eventPublisher))
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
            OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600), mapOf("sub" to subject)),
            OidcUserInfo(mapOf("sub" to subject))
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(oidcUser, "n/a", emptyList())
    }

    private fun stubDirectory() {
        whenever(service.directoryTree()).thenReturn(
            listOf(
                OrganizationTreeNode(
                    "o-root", "Klinik Favoriten",
                    listOf(OrganizationTreeNode("o-1", "Abteilung Anästhesiologie"))
                )
            )
        )
        whenever(service.typeOptions()).thenReturn(typeOptions)
    }

    // --- Page ---------------------------------------------------------------

    @Test
    @DisplayName("Page renders the whole directory tree and a fresh add form")
    fun pageRendersTreeAndForm() {
        authenticateAs("user-1")
        stubDirectory()

        mockMvc.perform(get("/admin/organizations"))
            .andExpect(status().isOk)
            .andExpect(view().name("organizations"))
            .andExpect(model().attribute("organizationName", ""))
            .andExpect(model().attribute("formMode", "add"))
            .andExpect(model().attribute("parentId", ""))
            .andExpect(model().attribute("parentName", ""))
            .andReturn()

        verify(service).directoryTree()
    }

    // --- Form / suggestions -------------------------------------------------

    @Test
    @DisplayName("Form endpoint returns a fresh add form")
    fun formReturnsFreshAddForm() {
        mockMvc.perform(get("/admin/organizations/form"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: form"))
            .andExpect(model().attribute("formMode", "add"))
            .andReturn()
    }

    @Test
    @DisplayName("Parent search returns organization suggestions")
    fun parentSearchReturnsSuggestions() {
        whenever(service.searchParentCandidates("Kardio"))
            .thenReturn(listOf(OrganizationOption("o-1", "Klinik Favoriten – Abteilung Kardiochirurgie")))

        mockMvc.perform(get("/admin/organizations/parents/search").param("q", "Kardio"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: parent-suggestions"))
            .andExpect(model().attribute("q", "Kardio"))
            .andReturn()
    }

    @Test
    @DisplayName("Parent select sets the parent combobox")
    fun parentSelectSetsBox() {
        mockMvc.perform(get("/admin/organizations/parents/select")
            .param("id", "o-1")
            .param("name", "Abteilung Kardiochirurgie"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: parentBox"))
            .andExpect(model().attribute("parentId", "o-1"))
            .andExpect(model().attribute("parentName", "Abteilung Kardiochirurgie"))
            .andReturn()
    }

    @Test
    @DisplayName("Type search returns matching organization type suggestions")
    fun typeSearchReturnsSuggestions() {
        whenever(service.typeOptions()).thenReturn(typeOptions)

        mockMvc.perform(get("/admin/organizations/types/search").param("q", "Abteilung"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: type-suggestions"))
            .andExpect(model().attribute("q", "Abteilung"))
            .andReturn()
    }

    @Test
    @DisplayName("Type select sets the type combobox")
    fun typeSelectSetsBox() {
        whenever(service.typeOptions()).thenReturn(typeOptions)

        mockMvc.perform(get("/admin/organizations/types/select").param("code", "abteilung"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: typeBox"))
            .andExpect(model().attribute("typeCode", "abteilung"))
            .andExpect(model().attribute("typeDisplay", "Abteilung"))
            .andReturn()
    }

    @Test
    @DisplayName("Type select rejects an unknown type code")
    fun typeSelectRejectsUnknownType() {
        mockMvc.perform(get("/admin/organizations/types/select").param("code", "unknown"))
            .andExpect(status().isBadRequest)
            .andReturn()
    }

    // --- Edit ---------------------------------------------------------------

    @Test
    @DisplayName("Edit loads the pre-filled edit form for an organization")
    fun editLoadsEditForm() {
        whenever(service.findOrganization("o-1")).thenReturn(
            OrganizationData(id = "o-1", name = "Abteilung Anästhesiologie", parentId = "o-root", type = OrganizationType("abteilung", "Abteilung"))
        )
        whenever(service.parentName("o-root")).thenReturn("Klinik Favoriten")
        whenever(service.typeOptions()).thenReturn(typeOptions)

        mockMvc.perform(get("/admin/organizations/o-1/edit"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: form"))
            .andExpect(model().attribute("formMode", "edit"))
            .andExpect(model().attribute("organizationId", "o-1"))
            .andExpect(model().attribute("organizationName", "Abteilung Anästhesiologie"))
            .andExpect(model().attribute("parentId", "o-root"))
            .andExpect(model().attribute("parentName", "Klinik Favoriten"))
            .andExpect(model().attribute("typeCode", "abteilung"))
            .andExpect(model().attribute("typeDisplay", "Abteilung"))
            .andReturn()
    }

    @Test
    @DisplayName("Edit loads the edit form for a top-level organization without a parent")
    fun editLoadsTopLevelEditForm() {
        whenever(service.findOrganization("o-root")).thenReturn(
            OrganizationData(id = "o-root", name = "Klinik Favoriten")
        )
        whenever(service.typeOptions()).thenReturn(typeOptions)

        mockMvc.perform(get("/admin/organizations/o-root/edit"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: form"))
            .andExpect(model().attribute("formMode", "edit"))
            .andExpect(model().attribute("organizationId", "o-root"))
            .andExpect(model().attribute("parentId", ""))
            .andExpect(model().attribute("parentName", ""))
            .andReturn()
    }

    @Test
    @DisplayName("Edit rejects an unknown organization")
    fun editRejectsUnknownOrganization() {
        whenever(service.findOrganization("unknown")).thenReturn(null)

        mockMvc.perform(get("/admin/organizations/unknown/edit"))
            .andExpect(status().isNotFound)
            .andReturn()
    }

    // --- Delete dialog ------------------------------------------------------

    @Test
    @DisplayName("Delete dialog loads the confirmation for an organization")
    fun deleteDialogLoadsConfirmation() {
        whenever(service.findOrganization("o-1")).thenReturn(
            OrganizationData(id = "o-1", name = "Abteilung Anästhesiologie")
        )

        mockMvc.perform(get("/admin/organizations/o-1/delete-dialog"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: delete-dialog"))
            .andExpect(model().attribute("deleteOrganizationId", "o-1"))
            .andExpect(model().attribute("deleteOrganizationName", "Abteilung Anästhesiologie"))
            .andReturn()
    }

    @Test
    @DisplayName("Delete dialog rejects an unknown organization")
    fun deleteDialogRejectsUnknownOrganization() {
        whenever(service.findOrganization("unknown")).thenReturn(null)

        mockMvc.perform(get("/admin/organizations/unknown/delete-dialog"))
            .andExpect(status().isNotFound)
            .andReturn()
    }

    // --- Create -------------------------------------------------------------

    @Test
    @DisplayName("Create persists the organization and refreshes the tree")
    fun createPersistsAndRefreshes() {
        whenever(service.create("Abteilung Anästhesiologie", "o-root", null))
            .thenReturn("o-2")

        mockMvc.perform(post("/admin/organizations")
            .param("name", "Abteilung Anästhesiologie")
            .param("parentId", "o-root"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: save-ok"))
            .andExpect(model().attribute("message", "Organisation 'Abteilung Anästhesiologie' hinzugefügt."))
            .andReturn()

        verify(service).create("Abteilung Anästhesiologie", "o-root", null)
    }

    @Test
    @DisplayName("Failed create re-renders the form with the error")
    fun failedCreateKeepsForm() {
        whenever(service.create("Fremd", "o-foreign", null))
            .thenThrow(IllegalArgumentException("Die übergeordnete Organisation existiert nicht."))
        whenever(service.parentName("o-foreign")).thenReturn(null)

        mockMvc.perform(post("/admin/organizations")
            .param("name", "Fremd")
            .param("parentId", "o-foreign"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: save-error"))
            .andExpect(model().attribute("error", "Die übergeordnete Organisation existiert nicht."))
            .andExpect(model().attribute("organizationName", "Fremd"))
            .andExpect(model().attribute("parentId", "o-foreign"))
            .andReturn()
    }

    // --- Update -------------------------------------------------------------

    @Test
    @DisplayName("Update persists the changes and refreshes the tree")
    fun updatePersistsAndRefreshes() {
        doNothing().whenever(service).update("o-1", "Abteilung Anästhesie", "o-root", OrganizationType("abteilung", "Abteilung"))
        whenever(service.typeOptions()).thenReturn(typeOptions)

        mockMvc.perform(post("/admin/organizations/o-1")
            .param("name", "Abteilung Anästhesie")
            .param("parentId", "o-root")
            .param("typeCode", "abteilung"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: save-ok"))
            .andExpect(model().attribute("message", "Organisation 'Abteilung Anästhesie' aktualisiert."))
            .andReturn()

        verify(service).update("o-1", "Abteilung Anästhesie", "o-root", OrganizationType("abteilung", "Abteilung"))
    }

    @Test
    @DisplayName("Update accepts an empty parent (top-level organization)")
    fun updateAcceptsEmptyParent() {
        doNothing().whenever(service).update("o-root", "Klinik Favoriten", "", null)

        mockMvc.perform(post("/admin/organizations/o-root")
            .param("name", "Klinik Favoriten"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: save-ok"))
            .andReturn()

        verify(service).update("o-root", "Klinik Favoriten", "", null)
    }

    @Test
    @DisplayName("Unknown type code is rejected")
    fun updateRejectsUnknownType() {
        mockMvc.perform(post("/admin/organizations/o-1")
            .param("name", "X")
            .param("parentId", "o-root")
            .param("typeCode", "unknown"))
            .andExpect(status().isBadRequest)
            .andReturn()
    }

    // --- Delete -------------------------------------------------------------

    @Test
    @DisplayName("Delete removes the organization and refreshes the tree")
    fun deleteRemovesAndRefreshes() {
        mockMvc.perform(post("/admin/organizations/o-1/delete"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: delete-ok"))
            .andExpect(model().attribute("message", "Organisation gelöscht."))
            .andReturn()

        verify(service).delete("o-1")
    }

    @Test
    @DisplayName("Failed delete returns an error notification")
    fun failedDeleteShowsError() {
        whenever(service.delete("o-1"))
            .thenThrow(IllegalArgumentException("Die Organisation hat noch Unterorganisationen."))

        mockMvc.perform(post("/admin/organizations/o-1/delete"))
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/organizations :: delete-error"))
            .andExpect(model().attribute("error", "Die Organisation hat noch Unterorganisationen."))
            .andReturn()
    }
}
