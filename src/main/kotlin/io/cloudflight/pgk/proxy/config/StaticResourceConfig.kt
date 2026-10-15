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

import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.LiteWebJarsResourceResolver
import org.springframework.web.servlet.resource.PathResourceResolver
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

@Configuration
class StaticResourceConfig(
    private val applicationContext: ApplicationContext
) {

    @Bean
    fun uiAssetsResourceHandler(): ResourceHttpRequestHandler =
        ResourceHttpRequestHandler().apply {
            setApplicationContext(this@StaticResourceConfig.applicationContext)
            setLocations(listOf(this@StaticResourceConfig.applicationContext.getResource("classpath:/static/")))
            afterPropertiesSet()
        }

    @Bean
    fun webjarsResourceHandler(): ResourceHttpRequestHandler =
        ResourceHttpRequestHandler().apply {
            setApplicationContext(this@StaticResourceConfig.applicationContext)
            setLocations(listOf(this@StaticResourceConfig.applicationContext.getResource("classpath:/META-INF/resources/webjars/")))
            setResourceResolvers(listOf(LiteWebJarsResourceResolver(), PathResourceResolver()))
            afterPropertiesSet()
        }

    @Bean
    fun uiAssetsHandlerMapping(
        uiAssetsResourceHandler: ResourceHttpRequestHandler,
        webjarsResourceHandler: ResourceHttpRequestHandler
    ): SimpleUrlHandlerMapping =
        SimpleUrlHandlerMapping().apply {
            order = Ordered.HIGHEST_PRECEDENCE
            setUrlMap(
                mapOf(
                    "/*.css" to uiAssetsResourceHandler,
                    "/*.js" to uiAssetsResourceHandler,
                    "/*.ico" to uiAssetsResourceHandler,
                    "/webjars/**" to webjarsResourceHandler
                )
            )
        }
}
