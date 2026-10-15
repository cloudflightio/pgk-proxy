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

package io.cloudflight.pgk.proxy.invitation.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface OrgRoleBlockRepository :
    JpaRepository<OrgRoleBlockEntity, OrgRoleBlockId> {

    @Query("select case when count(b) > 0 then true else false end from OrgRoleBlockEntity b " +
            "where b.id.direction = :direction and b.id.roleToken in :roleTokens")
    fun existsByDirectionAndRoleTokenIn(
        @Param("direction") direction: String,
        @Param("roleTokens") roleTokens: Collection<String>
    ): Boolean

    @Modifying
    @Query("delete from OrgRoleBlockEntity b where b.id.direction = :direction")
    fun deleteAllByDirection(@Param("direction") direction: String)
}
