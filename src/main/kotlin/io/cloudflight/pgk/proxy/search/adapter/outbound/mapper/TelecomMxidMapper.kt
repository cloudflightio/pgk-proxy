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

package io.cloudflight.pgk.proxy.search.adapter.outbound.mapper

import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.MxidTelecom
import org.hl7.fhir.r4.model.ContactPoint
import org.springframework.stereotype.Component

/**
 * Extracts a Matrix user id embedded in FHIR telecom contact points.
 */
@Component
class TelecomMxidMapper(
    private val properties: ProxyProperties
) {

    companion object {
        const val MXID_INDICATOR = "matrix:u/"
        const val TELECOM_SYSTEM = "url"
        val MXID_PATTERN =
            Regex("^${MXID_INDICATOR}[a-z0-9._=\\-/]+:[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
    }

    /**
     * @return the first mxid found in [telecom], or null when none matches
     */
    fun extractMxid(telecom: List<ContactPoint>): Mxid? =
        telecom.firstNotNullOfOrNull {
            if (it.system?.toCode() == properties.idp.telecomSystem) MxidTelecom.extractMxid(it.value) else null
        }
}
