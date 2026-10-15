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

package io.cloudflight.pgk.proxy.provisioning.domain.model

/**
 * Domain model of an Organization managed by an administrator in the FHIR provider directory.
 *
 * @param id FHIR resource id, absent while the organization has not been persisted yet
 * @param parentId id of the parent organization (the `partOf` reference); null for the root
 */
data class OrganizationData(
    val id: String? = null,
    val name: String,
    val parentId: String? = null,
    val type: OrganizationType? = null
)
