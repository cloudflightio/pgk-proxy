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
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.ui.application.VisibilityRow
import io.cloudflight.pgk.proxy.ui.application.VisibilityService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
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

class VisibilityControllerTest {

    private val visibilityService = mock(VisibilityService::class.java)
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(VisibilityController(visibilityService, AuditLogger()))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setViewResolvers(noOpViewResolver())
            .build()
    }

    /**
     * Standalone MockMvc has no Thymeleaf resolver; without one, the plain page view name
     * `visibility` falls through to the default internal-resource resolver and trips the
     * "circular view path" guard. A no-op resolver keeps these tests focused on the controller
     * (status/model/view name) without rendering templates.
     */
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

    @Test
    @DisplayName("Visibility page renders both the practitioner and healthcare-provider lists")
    fun pageRendersBothLists() {
        whenever(visibilityService.listPractitioners())
            .thenReturn(listOf(VisibilityRow("Practitioner/1", "Dr. Anna Berger", "@anna:matrix.pgk.example", "AB", Visibility.PUBLIC)))
        whenever(visibilityService.listHealthcareProviders())
            .thenReturn(listOf(VisibilityRow("HealthcareService/2", "Radiologie", "@radio:matrix.pgk.example", "R", Visibility.INTERNAL)))

        mockMvc.perform(get("/admin/visibility"))
            .andExpect(status().isOk)
            .andExpect(view().name("visibility"))
            .andExpect(model().attribute<List<VisibilityRow>>("practitioners", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(model().attribute<List<VisibilityRow>>("healthcareProviders", org.hamcrest.Matchers.hasSize(1)))
            .andReturn()

        verify(visibilityService).listPractitioners()
        verify(visibilityService).listHealthcareProviders()
    }

    @Test
    @DisplayName("Visibility change persists and returns the segment fragment")
    fun setVisibilityPersistsAndReturnsFragment() {
        mockMvc.perform(
            post("/admin/visibility")
                .param("id", "Practitioner/1")
                .param("visibility", "internal")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/visibility :: segment"))
            .andExpect(model().attribute("entryId", "Practitioner/1"))
            .andExpect(model().attribute("public", false))
            .andReturn()

        verify(visibilityService).setVisibility("Practitioner/1", Visibility.INTERNAL)
    }

    @Test
    @DisplayName("Failed visibility change returns the segment in its unchanged state with an error")
    fun failedSetVisibilityKeepsUnchangedState() {
        doThrow(RuntimeException("fhir down"))
            .whenever(visibilityService).setVisibility("Practitioner/1", Visibility.INTERNAL)

        mockMvc.perform(
            post("/admin/visibility")
                .param("id", "Practitioner/1")
                .param("visibility", "internal")
        )
            .andExpect(status().isOk)
            .andExpect(view().name("fragments/visibility :: segment-error"))
            .andExpect(model().attribute("entryId", "Practitioner/1"))
            .andExpect(model().attribute("public", true))
            .andExpect(model().attribute("error", "Änderung fehlgeschlagen"))
            .andReturn()
    }

    @Test
    @DisplayName("Unknown visibility value is rejected")
    fun unknownVisibilityIsRejected() {
        mockMvc.perform(
            post("/admin/visibility")
                .param("id", "Practitioner/1")
                .param("visibility", "unknown")
        )
            .andExpect(status().isBadRequest)
            .andReturn()
    }
}
