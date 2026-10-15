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

package io.cloudflight.pgk.proxy.provisioning.domain.model

/**
 * Type ("Art der Einrichtung") of an Organization in the provider directory, stored as a
 * FHIR Organization.type coding. The codes and displays come from the local HL7-at
 * organization type ValueSet (see `valuesets/organizations/ValueSet-hl7-at-organizationtype.json`).
 */
data class OrganizationType(
    val code: String,
    val display: String
) {
    companion object {
        const val SYSTEM = "https://termgit.elga.gv.at/ValueSet/hl7-at-organizationtype"
    }
}
