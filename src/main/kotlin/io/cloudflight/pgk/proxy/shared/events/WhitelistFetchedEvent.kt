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

package io.cloudflight.pgk.proxy.shared.events

import io.cloudflight.pgk.proxy.shared.Fqdn

/**
 * Published after a successful federation whitelist refresh, carrying the set of federation
 * endpoints (FQDNs) that are now allowed through the egress proxy.
 */
data class WhitelistFetchedEvent(val fqdns: Set<Fqdn>)