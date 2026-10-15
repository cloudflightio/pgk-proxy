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

package io.cloudflight.pgk.proxy.shared

import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Meta

/**
 * Security visibility of a directory entry, derived from FHIR metadata security tags.
 */
enum class Visibility(val code: String) {
    INTERNAL("internal"),
    PUBLIC("public");

    fun toCoding(): Coding = Coding(SYSTEM, code, null)

    companion object {
        const val SYSTEM = "https://pgk.elga.gv.at/CodeSystem/visibility"

        val DEFAULT = PUBLIC

        fun fromCode(code: String?): Visibility = entries.find { it.code == code } ?: DEFAULT

        /** @return the visibility encoded in [meta]'s security tags, or [DEFAULT] when absent/unknown */
        fun fromMeta(meta: Meta?): Visibility = meta?.security
            ?.firstNotNullOfOrNull { coding ->
                if (coding.system == SYSTEM) entries.find { it.code == coding.code } else null
            }
            ?: DEFAULT
    }
}
