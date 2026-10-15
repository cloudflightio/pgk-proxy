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

package io.cloudflight.pgk.proxy.search.adapter.inbound

import io.cloudflight.pgk.proxy.infrastructure.matrix.CurrentUserArgumentResolver
import io.cloudflight.pgk.proxy.infrastructure.matrix.WhoAmIPort
import io.cloudflight.pgk.proxy.search.application.UserSearchService
import io.cloudflight.pgk.proxy.search.domain.model.SearchResult
import io.cloudflight.pgk.proxy.search.domain.model.UserDirectoryEntry
import io.cloudflight.pgk.proxy.shared.Mxid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class SearchControllerTest {

    private val userSearchService = org.mockito.Mockito.mock(UserSearchService::class.java)
    private val whoAmI = org.mockito.Mockito.mock(WhoAmIPort::class.java)
    private val currentUser = Mxid("@alice:pgk.example")
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        whenever(whoAmI.whoami("mytoken")).thenReturn(currentUser)
        mockMvc = MockMvcBuilders
            .standaloneSetup(SearchController(userSearchService))
            .setCustomArgumentResolvers(CurrentUserArgumentResolver(whoAmI))
            .setMessageConverters(JacksonJsonHttpMessageConverter())
            .build()
    }

    private fun authenticated(content: String) = post("/_matrix/client/v3/user_directory/search")
        .header("Authorization", "Bearer mytoken")
        .contentType("application/json")
        .content(content)

    @Test
    @DisplayName("User directory search returns consolidated users as JSON")
    fun searchReturnsConsolidatedUsers() {
        whenever(userSearchService.search("alice", currentUser)).thenReturn(
            SearchResult(
                limited = false,
                results = listOf(
                    UserDirectoryEntry(userId = "@alice:pgk.example", displayName = "Alice Müller")
                )
            )
        )

        mockMvc.perform(authenticated("""{"search_term":"alice","limit":10}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.limited").value(false))
            .andExpect(jsonPath("$.results[0].user_id").value("@alice:pgk.example"))
            .andExpect(jsonPath("$.results[0].display_name").value("Alice Müller"))
    }

    @Test
    @DisplayName("User directory search returns empty results when nothing matches")
    fun searchReturnsEmptyResults() {
        whenever(userSearchService.search("nobody", currentUser))
            .thenReturn(SearchResult(limited = false, results = emptyList()))

        mockMvc.perform(authenticated("""{"search_term":"nobody","limit":10}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.limited").value(false))
            .andExpect(jsonPath("$.results").isEmpty())
    }

    @Test
    @DisplayName("User directory search forwards the search term to the service")
    fun searchForwardsSearchTermToService() {
        whenever(userSearchService.search("bob", currentUser))
            .thenReturn(SearchResult(limited = false, results = emptyList()))

        mockMvc.perform(authenticated("""{"search_term":"bob","limit":10}"""))
            .andExpect(status().isOk)
            .andReturn()

        org.mockito.Mockito.verify(userSearchService).search("bob", currentUser)
    }

    @Test
    @DisplayName("limit parameter is optional")
    fun searchLimitParameterIsOptional() {
        whenever(userSearchService.search("bob", currentUser))
            .thenReturn(SearchResult(limited = false, results = emptyList()))

        mockMvc.perform(authenticated("""{"search_term":"bob"}"""))
            .andExpect(status().isOk)
            .andReturn()

        org.mockito.Mockito.verify(userSearchService).search("bob", currentUser)
    }

    @Test
    @DisplayName("resolves the current user from the access token and passes it to the service")
    fun searchResolvesCurrentUserFromAccessToken() {
        whenever(userSearchService.search("alice", currentUser))
            .thenReturn(SearchResult(limited = false, results = emptyList()))

        mockMvc.perform(authenticated("""{"search_term":"alice","limit":10}"""))
            .andExpect(status().isOk)
            .andReturn()

        org.mockito.Mockito.verify(userSearchService).search("alice", currentUser)
    }
}
