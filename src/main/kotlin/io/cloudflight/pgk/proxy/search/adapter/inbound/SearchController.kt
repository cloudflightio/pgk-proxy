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

import io.cloudflight.pgk.proxy.infrastructure.matrix.CurrentUser
import io.cloudflight.pgk.proxy.search.application.UserSearchService
import io.cloudflight.pgk.proxy.search.domain.model.SearchRequest
import io.cloudflight.pgk.proxy.search.domain.model.SearchResult
import io.cloudflight.pgk.proxy.shared.Mxid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Matrix user-directory search endpoint.
 */
@RestController
@RequestMapping("/_matrix/client/v3/user_directory")
class SearchController(
    private val userSearchService: UserSearchService
) {

    /**
     * @return directory entries for [request]'s search term, filtered for the [currentUser]
     */
    @PostMapping("/search")
    fun search(
        @RequestBody request: SearchRequest,
        @CurrentUser currentUser: Mxid
    ): ResponseEntity<SearchResult> {
        val result = userSearchService.search(request.searchTerm, currentUser)
        return ResponseEntity.ok(result)
    }
}
