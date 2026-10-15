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

package io.cloudflight.pgk.keycloakprovider

import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ProxyClientTest {

    private val httpClient = mockk<HttpClient>()

    private val client = ProxyClient(
        ProxyEndpointConfig("http://proxy:8080", "the-secret", "pgk", "matrix.traefik.me", 60),
        httpClient
    )

    private val payload = IdpUserEventPayload(
        operation = "CREATE",
        userId = "user-1",
        mxid = "max"
    )

    private fun respondWith(statusCode: Int, body: String?): CapturingSlot<HttpRequest> {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns statusCode
        every { response.body() } returns body
        val request = slot<HttpRequest>()
        every { httpClient.send(capture(request), any<HttpResponse.BodyHandler<String>>()) } returns response
        return request
    }

    private fun failWith(exception: Exception) {
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws exception
    }

    @Test
    @DisplayName("A user event is posted to the proxy with HTTP Basic credentials")
    fun postsUserEventWithSecret() {
        val request = respondWith(202, "")

        client.sendUserEvent(payload)

        assertEquals("POST", request.captured.method())
        assertEquals("http://proxy:8080/internal/idp/user-events", request.captured.uri().toString())
        assertEquals(
            "Basic ${Base64.getEncoder().encodeToString("pgk-idp:the-secret".toByteArray())}",
            request.captured.headers().firstValue("Authorization").orElse(null)
        )
        assertEquals("application/json", request.captured.headers().firstValue("Content-Type").orElse(null))
    }

    @Test
    @DisplayName("A user event the proxy rejects does not fail the administration action")
    fun swallowsRejectedUserEvent() {
        respondWith(500, "boom")

        client.sendUserEvent(payload)
    }

    @Test
    @DisplayName("A user event does not fail when the proxy cannot be reached")
    fun swallowsTransportErrorOnUserEvent() {
        failWith(IOException("connection refused"))

        client.sendUserEvent(payload)
    }

    @Test
    @DisplayName("Display names are fetched with HTTP Basic credentials")
    fun fetchesDisplayNames() {
        val request = respondWith(
            200,
            """{"@max:matrix.traefik.me":"Mustermann, Max (AKH Wien)"}"""
        )

        val names = client.fetchDisplayNames()

        assertEquals("GET", request.captured.method())
        assertEquals("http://proxy:8080/internal/idp/display-names", request.captured.uri().toString())
        assertEquals(
            "Basic ${Base64.getEncoder().encodeToString("pgk-idp:the-secret".toByteArray())}",
            request.captured.headers().firstValue("Authorization").orElse(null)
        )
        assertEquals(mapOf("@max:matrix.traefik.me" to "Mustermann, Max (AKH Wien)"), names)
    }

    @Test
    @DisplayName("A failing display-name request yields an empty map")
    fun swallowsRejectedDisplayNameRequest() {
        respondWith(500, "boom")

        assertEquals(emptyMap<String, String>(), client.fetchDisplayNames())
    }

    @Test
    @DisplayName("Display-name fetching does not fail when the proxy cannot be reached")
    fun swallowsTransportErrorOnDisplayNames() {
        failWith(IOException("connection refused"))

        assertEquals(emptyMap<String, String>(), client.fetchDisplayNames())
    }
}
