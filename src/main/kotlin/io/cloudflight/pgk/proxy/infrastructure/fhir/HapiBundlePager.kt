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

package io.cloudflight.pgk.proxy.infrastructure.fhir

import ca.uhn.fhir.rest.client.api.IGenericClient
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.r4.model.Bundle
import org.springframework.modulith.NamedInterface

/**
 * Follows every `next` link of a FHIR search and collects all resources across pages.
 *
 * The caller builds and executes the first page (typically with [PAGE_SIZE] as `_count`) and hands
 * it to [allResources]; pagination stops once the server returns a bundle without a next link.
 *
 * Exposed to feature modules via the `Fhir` named interface of the `infrastructure` module.
 */
@NamedInterface("Fhir")
object HapiBundlePager {

    /** Page size used for searches that paginate through the whole result set. */
    const val PAGE_SIZE = 1000

    fun allResources(fhirClient: IGenericClient, firstPage: Bundle): List<IBaseResource> {
        val resources = mutableListOf<IBaseResource>()
        var bundle = firstPage
        while (true) {
            bundle.entry.orEmpty().forEach { entry -> entry.resource?.let(resources::add) }
            if (bundle.link.none { it.relation == Bundle.LINK_NEXT }) {
                return resources
            }
            bundle = fhirClient.loadPage().next(bundle).execute()
        }
    }
}
