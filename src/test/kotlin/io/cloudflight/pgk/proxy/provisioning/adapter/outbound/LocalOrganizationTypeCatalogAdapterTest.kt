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

package io.cloudflight.pgk.proxy.provisioning.adapter.outbound

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver

class LocalOrganizationTypeCatalogAdapterTest {

    private val resourcePatternResolver = mockk<ResourcePatternResolver>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val adapter = LocalOrganizationTypeCatalogAdapter(
        resourcePatternResolver = resourcePatternResolver,
        applicationEvents = eventPublisher
    )

    private fun localResource(json: String, filename: String = "ValueSet-hl7-at-organizationtype.json"): Resource {
        val resource = mockk<Resource>()
        every { resource.isReadable } returns true
        every { resource.filename } returns filename
        every { resource.inputStream } returns json.byteInputStream()
        return resource
    }

    @Test
    fun `loads every leaf concept as an organization type and skips the group header`() {
        every { resourcePatternResolver.getResources(any()) } returns arrayOf(
            localResource("""{
                "resourceType": "ValueSet",
                "url": "https://termgit.elga.gv.at/ValueSet/hl7-at-organizationtype",
                "concept": [
                    {
                        "code": "20",
                        "display": "Teil 2: Rollen für Organisationen",
                        "property": [ { "code": "child", "valueCode": "300" } ]
                    },
                    { "code": "300", "display": "Allgemeine Krankenanstalt", "property": [ { "code": "parent", "valueCode": "20" } ] },
                    { "code": "301", "display": "Sonderkrankenanstalt", "property": [ { "code": "parent", "valueCode": "20" } ] }
                ]
            }""")
        )

        val result = adapter.loadTypes()

        assertEquals(
            listOf(
                OrganizationType("300", "Allgemeine Krankenanstalt"),
                OrganizationType("301", "Sonderkrankenanstalt")
            ),
            result
        )
    }

    @Test
    fun `uses the code as display when the display is missing`() {
        every { resourcePatternResolver.getResources(any()) } returns arrayOf(
            localResource("""{
                "resourceType": "ValueSet",
                "url": "https://termgit.elga.gv.at/ValueSet/hl7-at-organizationtype",
                "concept": [ { "code": "300" } ]
            }""")
        )

        assertEquals(listOf(OrganizationType("300", "300")), adapter.loadTypes())
    }

    @Test
    fun `returns an empty list when the valueset cannot be read`() {
        val unreadable = mockk<Resource>()
        every { unreadable.isReadable } returns false

        every { resourcePatternResolver.getResources(any()) } returns arrayOf(unreadable)

        assertEquals(emptyList<OrganizationType>(), adapter.loadTypes())
    }
}
