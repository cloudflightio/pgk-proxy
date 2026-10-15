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
import ca.uhn.fhir.rest.gclient.IGetPage
import ca.uhn.fhir.rest.gclient.IGetPageTyped
import io.mockk.every
import io.mockk.mockk
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent
import org.hl7.fhir.r4.model.Bundle.BundleLinkComponent
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Resource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HapiBundlePagerTest {

    private val client = mockk<IGenericClient>()
    private val pageLoader = mockk<IGetPage>()

    private fun bundleOf(vararg resources: Resource, nextUrl: String? = null): Bundle =
        Bundle().apply {
            resources.forEach { addEntry(BundleEntryComponent().apply { resource = it }) }
            if (nextUrl != null) {
                link = listOf(BundleLinkComponent().apply {
                    relation = Bundle.LINK_NEXT
                    url = nextUrl
                })
            }
        }

    private fun practitioner(id: String): Practitioner = Practitioner().apply { this.id = id }

    private fun nextPageReturning(bundle: Bundle): IGetPageTyped<Bundle> {
        val typed = mockk<IGetPageTyped<Bundle>>()
        every { typed.execute() } returns bundle
        return typed
    }

    @Test
    fun `returns all resources of a single page`() {
        val first = bundleOf(practitioner("1"), practitioner("2"))

        val resources = HapiBundlePager.allResources(client, first)

        assertEquals(listOf("1", "2"), resources.map { it.idElement.idPart })
    }

    @Test
    fun `follows next links across all pages`() {
        val first = bundleOf(practitioner("1"), practitioner("2"), nextUrl = "page-2")
        val second = bundleOf(practitioner("3"), nextUrl = "page-3")
        val third = bundleOf(practitioner("4"))
        every { client.loadPage() } returns pageLoader
        every { pageLoader.next<Bundle>(any()) } returnsMany listOf(
            nextPageReturning(second),
            nextPageReturning(third)
        )

        val resources = HapiBundlePager.allResources(client, first)

        assertEquals(listOf("1", "2", "3", "4"), resources.map { it.idElement.idPart })
    }

    @Test
    fun `returns empty when the bundle has no entries`() {
        val resources = HapiBundlePager.allResources(client, bundleOf())

        assertTrue(resources.isEmpty())
    }
}
