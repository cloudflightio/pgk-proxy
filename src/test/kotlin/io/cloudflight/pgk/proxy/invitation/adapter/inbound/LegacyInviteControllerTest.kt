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

package io.cloudflight.pgk.proxy.invitation.adapter.inbound

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class LegacyInviteControllerTest {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(LegacyInviteController())
            .build()
    }

    @Test
    @DisplayName("returns 403 for the legacy v1 federation invite endpoint")
    fun returns403ForLegacyV1Invite() {
        mockMvc.perform(put("/_matrix/federation/v1/invite/room1/evt1")
            .contentType("application/json")
            .content("""{"origin":"matrix.partner.example","event":{"type":"m.room.member"}}"""))
            .andExpect(status().isForbidden)
            .andExpect(content().json(
                """{"errcode": "M_FORBIDDEN", "error": "Legacy federation invite endpoint (v1) is disabled."}"""
            ))
    }
}
