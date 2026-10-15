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

package io.cloudflight.pgk.proxy.provisioning.application

import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUser
import io.cloudflight.pgk.proxy.provisioning.domain.model.IdpUserOperation
import io.cloudflight.pgk.proxy.provisioning.port.FindUserIdsByMxidPort
import io.cloudflight.pgk.proxy.provisioning.port.RemovePractitionerPort
import io.cloudflight.pgk.proxy.provisioning.port.WritePractitionerPort
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.stereotype.Service

@Service
class IdpProvisioningService(
    private val writePractitioner: WritePractitionerPort,
    private val removePractitioner: RemovePractitionerPort,
    private val findUserIdsByMxid: FindUserIdsByMxidPort,
    private val auditLogger: AuditLogger
) {

    fun apply(user: IdpUser, operation: IdpUserOperation) {
        if (operation == IdpUserOperation.DELETE || (operation == IdpUserOperation.UPDATE && user.mxid == null)) {
            removePractitioner.remove(user.userId)
            auditLogger.info("system", AuditAction.IdpProvisionRemove, "Removed practitioner for IDP user ${user.userId}")
            return
        }

        if (user.mxid == null) {
            auditLogger.info(
                "system", AuditAction.IdpProvisionSkip,
                "Skipping FHIR provisioning for IDP user ${user.userId} without mxid"
            )
            return
        }

        ensureMxidAvailable(user.mxid, user.userId)
        writePractitioner.write(user)
        auditLogger.info("system", AuditAction.IdpProvisionWrite, "Wrote practitioner for IDP user ${user.userId}")
    }

    private fun ensureMxidAvailable(mxid: Mxid, userId: String) {
        require(findUserIdsByMxid.findUserIdsByMxid(mxid).all { it == userId }) {
            "Matrix user id $mxid is assigned to another practitioner"
        }
    }
}
