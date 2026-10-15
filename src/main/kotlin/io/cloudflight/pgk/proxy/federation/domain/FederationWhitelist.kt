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

package io.cloudflight.pgk.proxy.federation.domain

import io.cloudflight.pgk.proxy.shared.Fqdn
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
class FederationWhitelist {

    private val allowed = AtomicReference<Set<Fqdn>>(emptySet())

    fun isAllowed(fqdn: Fqdn): Boolean = allowed.get().contains(fqdn)

    fun replace(newSet: Set<Fqdn>) {
        allowed.set(newSet)
    }

    fun size(): Int = allowed.get().size
}
