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
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import io.cloudflight.pgk.proxy.shared.TrustLevel
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

@Component
class PractitionerWriteMapper(
    private val properties: ProxyProperties
) {

    fun toFhir(user: IdpUser): Practitioner {
        val mxid = requireNotNull(user.mxid) { "Cannot map IDP user ${user.userId} without an mxid" }
        return Practitioner().apply {
            meta = Meta().apply {
                addProfile(properties.idp.practitionerProfile)
                addSecurity(TrustLevel.TL_2.toCoding())
            }
            active = true
            addIdentifier(Identifier().setSystem(properties.idp.identifierSystem).setValue(user.userId))
            addName(HumanName().apply {
                use = HumanName.NameUse.OFFICIAL
                user.lastName?.let { family = it }
                user.firstName?.let { addGiven(it) }
            })
            addTelecom(ContactPoint().apply {
                system = ContactPoint.ContactPointSystem.fromCode(properties.idp.telecomSystem)
                use = ContactPoint.ContactPointUse.WORK
                value = MxidTelecom.toTelecomValue(mxid)
            })
        }
    }

}
