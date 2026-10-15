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

package io.cloudflight.pgk.proxy.config

import org.flywaydb.core.Flyway
import org.springframework.boot.LazyInitializationExcludeFilter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Runs Flyway eagerly even though `spring.main.lazy-initialization` is on, so migrations
 * apply at startup instead of lazily on the first database access (which would race the
 * first admin-page request against an unmigrated schema).
 */
@Configuration
@ConditionalOnClass(Flyway::class)
class DatabaseConfig {

    @Bean
    @ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "true", matchIfMissing = true)
    fun flywayLazyInitializationExcludeFilter(): LazyInitializationExcludeFilter =
        LazyInitializationExcludeFilter.forBeanTypes(Flyway::class.java)
}
