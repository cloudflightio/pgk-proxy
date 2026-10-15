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

package io.cloudflight.pgk.proxy.search.domain.service

import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.shared.Mxid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityConsolidatorTest {

    private class TestConsolidator : EntityConsolidator<Unit, Unit>() {
        override fun consolidate(bundle: Unit, mxid: Mxid): Unit? = null
        fun walk(organization: RawOrganization?, index: Map<String, RawOrganization>): OrgChain =
            getOrganizationChain(organization, index)
    }

    private val consolidator = TestConsolidator()

    private fun org(id: String, partOf: String? = null) = RawOrganization(
        id = id,
        name = "Org $id",
        partOfReference = partOf
    )

    @Test
    fun `walks a single-level chain`() {
        val index = mapOf("org-child" to org("org-child"))

        val chain = consolidator.walk(org("org-child"), index)

        assertEquals(listOf("org-child"), chain.organizationChain.map { it.id })
    }

    @Test
    fun `walks multiple levels from child to parent`() {
        val child = org("org-child", "org-parent")
        val parent = org("org-parent", "org-root")
        val root = org("org-root")
        val index = mapOf(
            "org-child" to child,
            "org-parent" to parent,
            "org-root" to root
        )

        val chain = consolidator.walk(child, index)

        assertEquals(listOf("org-child", "org-parent", "org-root"), chain.organizationChain.map { it.id })
    }

    @Test
    fun `caps the chain at the maximum depth`() {
        val orgs = (1..6).associate { i ->
            val id = "org-$i"
            val parent = if (i == 6) null else "org-${i + 1}"
            id to org(id, parent)
        }

        val chain = consolidator.walk(orgs.getValue("org-1"), orgs)

        assertEquals(3, chain.organizationChain.size)
        assertEquals(listOf("org-1", "org-2", "org-3"), chain.organizationChain.map { it.id })
    }

    @Test
    fun `terminates on circular references`() {
        val a = org("org-a", "org-b")
        val b = org("org-b", "org-a")
        val index = mapOf("org-a" to a, "org-b" to b)

        val chain = consolidator.walk(a, index)

        assertEquals(2, chain.organizationChain.size)
        assertEquals(listOf("org-a", "org-b"), chain.organizationChain.map { it.id })
    }

    @Test
    fun `stops gracefully on dangling partOf reference`() {
        val child = org("org-child", "org-missing")
        val index = mapOf("org-child" to child)

        val chain = consolidator.walk(child, index)

        assertEquals(listOf("org-child"), chain.organizationChain.map { it.id })
    }

    @Test
    fun `returns empty chain for null organization`() {
        val chain = consolidator.walk(null, emptyMap())

        assertTrue(chain.organizationChain.isEmpty())
    }

    @Test
    fun `display joins org names with spaces`() {
        val child = org("org-child", "org-parent")
        val parent = org("org-parent")
        val index = mapOf("org-child" to child, "org-parent" to parent)

        val chain = consolidator.walk(child, index)

        assertEquals("Org org-child Org org-parent", chain.display())
    }
}
