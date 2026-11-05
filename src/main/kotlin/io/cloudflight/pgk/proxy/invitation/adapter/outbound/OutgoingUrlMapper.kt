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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound

import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

interface OutgoingUrlMapper {
    fun mapToOutgoingUrl(url: String): String
}

@Component
@Profile("local")
@Primary
internal class LocalOutgoingUrlMapper : OutgoingUrlMapper {
    // override the http in the local dev setup because the PGK proxy runs outside the docker network
    // but the matrix servers assume the federation is routed internally
    override fun mapToOutgoingUrl(url: String): String = url.replaceFirst("http", "https")
}

@Component
internal class DefaultOutgoingUrlMapper : OutgoingUrlMapper {
    override fun mapToOutgoingUrl(url: String): String = url
}
