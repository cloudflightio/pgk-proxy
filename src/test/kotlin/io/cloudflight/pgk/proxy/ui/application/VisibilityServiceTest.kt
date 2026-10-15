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

package io.cloudflight.pgk.proxy.ui.application

import io.cloudflight.pgk.proxy.search.api.domain.model.VisibilityEntry
import io.cloudflight.pgk.proxy.search.api.port.HealthcareServiceVisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityQueryPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityUpdatePort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.VisibilityLoadFailedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class VisibilityServiceTest {

    private val visibilityQuery = mockk<VisibilityQueryPort>()
    private val healthcareServiceVisibilityQuery = mockk<HealthcareServiceVisibilityQueryPort>()
    private val visibilityUpdate = mockk<VisibilityUpdatePort>()
    private val eventPublisher = mockk<ApplicationEventPublisher>()
    private val service = VisibilityService(visibilityQuery, healthcareServiceVisibilityQuery, visibilityUpdate, eventPublisher)

    @Test
    fun `practitioner list maps entries and shows the mxid as subline`() {
        every { visibilityQuery.listProvisionedPractitioners() } returns listOf(
            VisibilityEntry("Practitioner/1", "Dr. Anna Berger", Mxid("@anna:matrix.pgk.example"), Visibility.PUBLIC)
        )

        val result = service.listPractitioners()

        assertEquals(
            listOf(
                VisibilityRow(
                    id = "Practitioner/1",
                    name = "Dr. Anna Berger",
                    subline = "@anna:matrix.pgk.example",
                    initials = "AB",
                    visibility = Visibility.PUBLIC
                )
            ),
            result
        )
    }

    @Test
    fun `healthcare provider list maps entries`() {
        every { healthcareServiceVisibilityQuery.listProvisionedHealthcareServices() } returns listOf(
            VisibilityEntry("HealthcareService/2", "Radiologie", Mxid("@radio:matrix.pgk.example"), Visibility.INTERNAL)
        )

        val result = service.listHealthcareProviders()

        assertEquals("HealthcareService/2", result.single().id)
        assertEquals("Radiologie", result.single().name)
        assertEquals("R", result.single().initials)
        assertEquals(Visibility.INTERNAL, result.single().visibility)
    }

    @Test
    fun `setVisibility delegates to the update port`() {
        every { visibilityUpdate.setVisibility("Practitioner/1", Visibility.INTERNAL) } returns Unit

        service.setVisibility("Practitioner/1", Visibility.INTERNAL)

        verify(exactly = 1) { visibilityUpdate.setVisibility("Practitioner/1", Visibility.INTERNAL) }
    }

    @Test
    fun `publishes event when practitioner list fails to load`() {
        every { eventPublisher.publishEvent(any<Any>()) } returns Unit
        every { visibilityQuery.listProvisionedPractitioners() } throws IllegalStateException("boom")

        val result = service.listPractitioners()

        assertEquals(emptyList<VisibilityRow>(), result)
        verify(exactly = 1) {
            eventPublisher.publishEvent(match<VisibilityLoadFailedEvent> { it.context == "practitioners" })
        }
    }

    @Test
    fun `publishes event when healthcare provider list fails to load`() {
        every { eventPublisher.publishEvent(any<Any>()) } returns Unit
        every { healthcareServiceVisibilityQuery.listProvisionedHealthcareServices() } throws IllegalStateException("boom")

        val result = service.listHealthcareProviders()

        assertEquals(emptyList<VisibilityRow>(), result)
        verify(exactly = 1) {
            eventPublisher.publishEvent(match<VisibilityLoadFailedEvent> { it.context == "healthcare services" })
        }
    }
}
