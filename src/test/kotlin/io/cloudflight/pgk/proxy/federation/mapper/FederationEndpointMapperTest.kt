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

package io.cloudflight.pgk.proxy.federation.mapper

import io.cloudflight.pgk.proxy.shared.Fqdn
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.hl7.fhir.r4.model.Endpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FederationEndpointMapperTest {

    private val mapper = FederationEndpointMapper()

    private fun endpoint(address: String, trustLevel: TrustLevel): Endpoint =
        Endpoint().apply {
            this.address = address
            meta.addSecurity(trustLevel.toCoding())
        }

    @Test
    @DisplayName("accepts a tl-4 endpoint and extracts the FQDN from a URL")
    fun testAcceptsTl4EndpointAndExtractsFqdnFromUrl() {
        val result = mapper.toDomain(endpoint("https://matrix.pgk.example", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
        assertEquals(TrustLevel.TL_4, result?.trustLevel)
    }

    @Test
    @DisplayName("accepts a tl-4 endpoint and extracts the FQDN from a bare host")
    fun testAcceptsTl4EndpointAndExtractsFqdnFromBareHost() {
        val result = mapper.toDomain(endpoint("matrix.pgk.example", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("accepts a tl-4 endpoint and extracts the FQDN from a URL with port and path")
    fun testAcceptsTl4EndpointAndExtractsFqdnFromUrlWithPortAndPath() {
        val result = mapper.toDomain(endpoint("https://matrix.pgk.example:8448/_matrix", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("extracts the FQDN from a matrix:// scheme")
    fun testExtractsFqdnFromMatrixScheme() {
        val result = mapper.toDomain(endpoint("matrix://matrix.pgk.example", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("extracts the FQDN from a URL with a trailing slash")
    fun testExtractsFqdnFromUrlWithTrailingSlash() {
        val result = mapper.toDomain(endpoint("https://matrix.pgk.example/", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("extracts the FQDN from a bare host with a port")
    fun testExtractsFqdnFromBareHostWithPort() {
        val result = mapper.toDomain(endpoint("matrix.pgk.example:8448", TrustLevel.TL_4))

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("extracts the FQDN preserving the case of the address")
    fun testExtractsFqdnPreservingAddressCase() {
        val result = mapper.toDomain(endpoint("https://MATRIX.PGK.EXAMPLE", TrustLevel.TL_4))

        assertEquals(Fqdn("MATRIX.PGK.EXAMPLE"), result?.fqdn)
    }

    @Test
    @DisplayName("filters out endpoints with a trust level below tl-4")
    fun testFiltersOutEndpointsBelowTl4() {
        assertNull(mapper.toDomain(endpoint("https://matrix.pgk.example", TrustLevel.TL_1)))
        assertNull(mapper.toDomain(endpoint("https://matrix.pgk.example", TrustLevel.TL_2)))
        assertNull(mapper.toDomain(endpoint("https://matrix.pgk.example", TrustLevel.TL_3)))
    }

    @Test
    @DisplayName("accepts a tl-4 endpoint that carries additional lower trust level labels")
    fun testAcceptsTl4EndpointWithAdditionalLowerTrustLevelLabels() {
        val result = mapper.toDomain(
            Endpoint().apply {
                address = "https://matrix.pgk.example"
                meta.addSecurity(TrustLevel.TL_4.toCoding())
                meta.addSecurity(TrustLevel.TL_1.toCoding())
            }
        )

        assertEquals(Fqdn("matrix.pgk.example"), result?.fqdn)
    }

    @Test
    @DisplayName("filters out endpoints without a trust level")
    fun testFiltersOutEndpointsWithoutTrustLevel() {
        assertNull(mapper.toDomain(Endpoint().apply { address = "https://matrix.pgk.example" }))
    }

    @Test
    @DisplayName("filters out endpoints with a trust level below tl-4 under the trust level system")
    fun testFiltersOutEndpointsWithLowerTrustLevelUnderTrustLevelSystem() {
        assertNull(mapper.toDomain(endpoint("https://matrix.pgk.example", TrustLevel.TL_2)))
    }

    @Test
    @DisplayName("returns null for a blank address")
    fun testReturnsNullForBlankAddress() {
        assertNull(mapper.toDomain(endpoint("", TrustLevel.TL_4)))
    }

    @Test
    @DisplayName("returns null for a missing address")
    fun testReturnsNullForMissingAddress() {
        assertNull(mapper.toDomain(Endpoint().apply { meta.addSecurity(TrustLevel.TL_4.toCoding()) }))
    }

    @Test
    @DisplayName("returns null for an address without a valid FQDN")
    fun testReturnsNullForInvalidAddress() {
        assertNull(mapper.toDomain(endpoint("not a url", TrustLevel.TL_4)))
        assertNull(mapper.toDomain(endpoint("file:///path", TrustLevel.TL_4)))
    }

    @Test
    @DisplayName("returns null for a single-label host without a dot")
    fun testReturnsNullForSingleLabelHost() {
        assertNull(mapper.toDomain(endpoint("https://localhost", TrustLevel.TL_4)))
    }
}
