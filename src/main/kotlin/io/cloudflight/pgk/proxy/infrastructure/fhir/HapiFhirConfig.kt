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

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.rest.client.api.IGenericClient
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HapiFhirConfig(
    private val properties: ProxyProperties,
    private val auditLogger: AuditLogger
) {

    @Bean
    fun fhirContext(): FhirContext = FhirContext.forR4().apply {
        restfulClientFactory.serverValidationMode = ServerValidationModeEnum.NEVER
    }

    @Bean
    fun fhirClient(fhirContext: FhirContext): IGenericClient {
        val client = fhirContext.newRestfulGenericClient(properties.fhir.baseUrl)

        auditLogger.info(
            "system", AuditAction.FhirClientInit,
            "Using FHIR client with baseUrl: ${properties.fhir.baseUrl}"
        )

        val auth = properties.fhir.auth
        if (auth.username.isNotBlank() && auth.password.isNotBlank()) {
            client.registerInterceptor(BasicAuthInterceptor(auth.username, auth.password))
        }

        if (properties.fhir.logCalls) {
            client.registerInterceptor(
                LoggingInterceptor().apply {
                    setLogRequestSummary(true)
                    setLogResponseSummary(true)
                }
            )
        }

        return client
    }
}
