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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrustLevelTest {

    @Test
    fun `maps known level strings to enum values`() {
        assertEquals(TrustLevel.TL_1, TrustLevel.fromString("tl-1"))
        assertEquals(TrustLevel.TL_2, TrustLevel.fromString("tl-2"))
        assertEquals(TrustLevel.TL_3, TrustLevel.fromString("tl-3"))
        assertEquals(TrustLevel.TL_4, TrustLevel.fromString("tl-4"))
    }

    @Test
    fun `defaults to TL 1 for unknown level strings`() {
        assertEquals(TrustLevel.TL_1, TrustLevel.fromString("tl-99"))
        assertEquals(TrustLevel.TL_1, TrustLevel.fromString("unknown"))
    }
}
