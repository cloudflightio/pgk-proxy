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

package io.cloudflight.pgk.proxy.invitation.mapper

import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.PractitionerRole
import org.hl7.fhir.r4.model.Reference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PractitionerRoleMapperTest {

    private val mapper = PractitionerRoleMapper()

    @Test
    @DisplayName("maps a FHIR practitioner role to the invitation role info")
    fun mapsFhirRoleToInvitationRoleInfo() {
        val role = PractitionerRole()
        role.codeFirstRep.addCoding(Coding("https://pgk.cs", "HAP", "Hausarzt"))
        role.meta.addSecurity(TrustLevel.TL_2.toCoding())
        role.organization = Reference("Organization/1")

        val info = mapper.toDomain(role)

        assertEquals("HAP", info.code)
        assertEquals("https://pgk.cs", info.codeSystem)
        assertEquals(TrustLevel.TL_2, info.trustLevel)
        assertEquals("Organization/1", info.organizationReference)
    }

    @Test
    @DisplayName("falls back to empty code and default trust level when absent")
    fun fallsBackToDefaults() {
        val role = PractitionerRole()
        role.organization = Reference("Organization/1")

        val info = mapper.toDomain(role)

        assertEquals("", info.code)
        assertEquals("", info.codeSystem)
        assertEquals(TrustLevel.TL_1, info.trustLevel)
        assertEquals("Organization/1", info.organizationReference)
    }
}
