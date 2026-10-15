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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PractitionerWriteMapperTest {
    private val properties = ProxyProperties(idp = ProxyProperties.IdpProperties(
        identifierSystem = "https://local/practitioner",
        practitionerProfile = "https://local/fhir/StructureDefinition/practitioner",
        telecomSystem = "url"
    ))
    private val mapper = PractitionerWriteMapper(properties)
    private val user = IdpUser("1b7d4a4e-0000-4000-8000-000000000001", "max", "Max", "Mustermann", Mxid("@max:example.org"))

    @Test
    fun `writes Keycloak UUID as the single identifier without a Keycloak tag`() {
        val practitioner = mapper.toFhir(user)

        assertEquals(1, practitioner.identifier.size)
        assertEquals("https://local/practitioner", practitioner.identifier.single().system)
        assertEquals("1b7d4a4e-0000-4000-8000-000000000001", practitioner.identifier.single().value)
        assertEquals("https://local/fhir/StructureDefinition/practitioner", practitioner.meta.profile.single().value)
        assertTrue(practitioner.meta.tag.isEmpty())
        assertEquals("matrix:u/max:example.org", practitioner.telecom.single().value)
        assertEquals("url", practitioner.telecom.single().system.toCode())
        assertEquals(TrustLevel.TL_2, TrustLevel.fromMeta(practitioner.meta))
    }
}
