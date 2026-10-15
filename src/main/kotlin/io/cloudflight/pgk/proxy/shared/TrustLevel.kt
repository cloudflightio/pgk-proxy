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

enum class TrustLevel(val code: String) {
    TL_1("tl-1"),
    TL_2("tl-2"),
    TL_3("tl-3"),
    TL_4("tl-4");

    val level: String get() = code

    fun toCoding(): Coding = Coding(SYSTEM, code, null)

    companion object {
        const val SYSTEM = "https://pgk.elga.gv.at/CodeSystem/trust-level"

        val DEFAULT = TL_1

        fun fromCode(code: String?): TrustLevel = entries.find { it.code == code } ?: DEFAULT

        fun fromString(value: String): TrustLevel = entries.find { it.level == value } ?: TL_1

        fun fromMeta(meta: Meta?): TrustLevel = fromCode(
            meta?.security
                ?.find { it.system?.contains("trust-level") == true }
                ?.code
        )
    }
}
