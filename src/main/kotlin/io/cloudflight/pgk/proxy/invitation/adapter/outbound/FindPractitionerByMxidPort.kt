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

import io.cloudflight.pgk.proxy.invitation.domain.model.PractitionerDirectoryEntry
import io.cloudflight.pgk.proxy.shared.Mxid

/**
 * Looks up the directory entry of the practitioner carrying the telecom value of [mxid].
 * Unlike [PractitionerDirectoryPort] this does not require the IDP identifier, so
 * enforcement can resolve a user by their Matrix mxid alone (mirrors the role lookup).
 */
interface FindPractitionerByMxidPort {
    fun findByMxid(mxid: Mxid): PractitionerDirectoryEntry?
}
