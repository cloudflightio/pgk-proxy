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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound

import ca.uhn.fhir.rest.api.SummaryEnum
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.gclient.TokenClientParam
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.invitation.port.UserExistsInDirectoryPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.HealthcareService
import org.hl7.fhir.r4.model.Practitioner
import org.springframework.stereotype.Component

/**
 * Checks whether a user is known in the Provider Directory, i.e. whether a
 * Practitioner or HealthcareService resource references the given mxid via a
 * telecom contact point.
 */
@Component
class HapiFhirDirectoryUserAdapter(
    private val fhirClient: IGenericClient,
    private val properties: ProxyProperties
) : UserExistsInDirectoryPort {

    override fun exists(mxid: Mxid): Boolean =
        existsPractitioner(mxid) || existsHealthcareService(mxid)

    private fun existsByTelecom(resourceType: Class<out IBaseResource>, param: TokenClientParam, mxid: Mxid): Boolean =
        fhirClient.search<Bundle>()
            .forResource(resourceType)
            .where(param.exactly().systemAndCode(properties.idp.telecomSystem, MxidTelecom.toTelecomValue(mxid)))
            .summaryMode(SummaryEnum.COUNT)
            .returnBundle(Bundle::class.java)
            .execute()
            .total > 0

    private fun existsPractitioner(mxid: Mxid) =
        existsByTelecom(Practitioner::class.java, Practitioner.TELECOM, mxid)

    private fun existsHealthcareService(mxid: Mxid) =
        existsByTelecom(HealthcareService::class.java, TokenClientParam("telecom"), mxid)
}
