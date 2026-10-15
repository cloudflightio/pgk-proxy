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

package io.cloudflight.pgk.proxy.search.application

import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedRoleQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.OrgBlockedUserQueryPort
import io.cloudflight.pgk.proxy.invitation.api.port.UserInvitationRightsByMxidQueryPort
import io.cloudflight.pgk.proxy.search.api.port.CurrentUserMxidQueryPort
import io.cloudflight.pgk.proxy.search.api.port.CurrentUserPractitionerQueryPort
import io.cloudflight.pgk.proxy.search.api.port.PractitionerSearchPort
import io.cloudflight.pgk.proxy.search.api.port.VisibilityByMxidQueryPort
import io.cloudflight.pgk.proxy.config.ProxyProperties
import io.cloudflight.pgk.proxy.search.domain.model.ConsolidatedUser
import io.cloudflight.pgk.proxy.search.domain.model.OrgChain
import io.cloudflight.pgk.proxy.search.domain.model.PractitionerSearchBundle
import io.cloudflight.pgk.proxy.search.domain.model.SearchResult
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawOrganization
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitioner
import io.cloudflight.pgk.proxy.search.domain.model.raw.RawPractitionerRole
import io.cloudflight.pgk.proxy.search.domain.service.HealthcareServiceConsolidator
import io.cloudflight.pgk.proxy.search.domain.service.PractitionerConsolidator
import io.cloudflight.pgk.proxy.search.port.*
import io.cloudflight.pgk.proxy.shared.InvitationDirection
import io.cloudflight.pgk.proxy.shared.Mxid
import io.cloudflight.pgk.proxy.shared.Visibility
import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import io.cloudflight.pgk.proxy.shared.searchTermsOf
import org.springframework.stereotype.Service

/**
 * Orchestrates the user-directory search: resolves the current user, fetches and
 * consolidates candidates, and hides internal users outside their organization.
 */
@Service
class UserSearchService(
    private val practitionersPort: SearchPractitionersByNamePort,
    private val practitionersMxidPort: SearchPractitionersByMxidPort,
    private val practitionersIdentifierPort: SearchPractitionersByIdentifierPort,
    private val healthcareServicesMxidPort: SearchHealthcareServicesByMxidPort,
    private val healthcareServicesPort: SearchHealthcareServicesPort,
    private val practitionerConsolidator: PractitionerConsolidator,
    private val healthcareServiceConsolidator: HealthcareServiceConsolidator,
    private val findOrganizationById: FindOrganizationByIdPort,
    private val properties: ProxyProperties,
    private val blockedUserPort: OrgBlockedUserQueryPort,
    private val blockedRolePort: OrgBlockedRoleQueryPort,
    private val userInvitationRightsPort: UserInvitationRightsByMxidQueryPort,
    private val auditLogger: AuditLogger
) : PractitionerSearchPort, CurrentUserPractitionerQueryPort, CurrentUserMxidQueryPort, VisibilityByMxidQueryPort {

    /**
     * Searches practitioners by name and returns a raw entry per practitioner/root-org pair.
     *
     * @param query the search term; empty results when blank
     * @return list of practitioner maps (id, name, trustLevel, organizationId, organizationName)
     */
    override fun searchPractitioners(query: String): List<Map<String, Any>> {
        if (query.isBlank()) return emptyList()
        val terms = searchTermsOf(query)
        val byName = toPractitionerEntries(practitionersPort.search(query), requireMxid = true, terms = terms)
        val mxid = runCatching { Mxid(query) }.getOrNull()
        val byMxid = if (mxid == null) emptyList()
        else toPractitionerEntries(practitionersMxidPort.search(mxid), requireMxid = true, terms = emptyList())
        return (byName + byMxid).distinctBy { it["id"] to it["organizationId"] }
    }

    override fun findPractitionerByUserId(userId: String): Map<String, Any>? {
        if (userId.isBlank()) return null
        val bundle =
            practitionersIdentifierPort.searchByIdentifier(properties.idp.identifierSystem, userId)
        if (bundle.practitioners.isEmpty()) {
            auditLogger.warn(
                "system", AuditAction.SearchPractitionerNotFound,
                "No practitioner found for IDP user $userId (identifier ${properties.idp.identifierSystem}|$userId)"
            )
            return null
        }
        return toPractitionerEntries(bundle, requireMxid = false, terms = emptyList()).firstOrNull()
    }

    override fun findMxidByUserId(userId: String): Mxid? {
        if (userId.isBlank()) return null
        val bundle =
            practitionersIdentifierPort.searchByIdentifier(properties.idp.identifierSystem, userId)
        return bundle.practitioners.firstNotNullOfOrNull { it.mxid }
    }

    override fun visibilityOf(mxid: Mxid): Visibility? {
        val practitioners = practitionersMxidPort.search(mxid).practitioners.filter { it.mxid == mxid }
        val services = healthcareServicesMxidPort.search(mxid).healthcareServices.filter { it.mxid == mxid }
        // A user can be represented by several directory resources; if any of them is internal,
        // the mxid must be treated as internal so no profile leaks through a stale duplicate.
        if (practitioners.any { it.visibility == Visibility.INTERNAL } ||
            services.any { it.visibility == Visibility.INTERNAL }
        ) {
            return Visibility.INTERNAL
        }
        val practitioner = practitioners.firstOrNull()
        if (practitioner != null) return practitioner.visibility
        return services.firstOrNull()?.visibility
    }

    private fun toPractitionerEntries(
        bundle: PractitionerSearchBundle,
        requireMxid: Boolean,
        terms: List<String>
    ): List<Map<String, Any>> {
        val organizationsById = bundle.organizations.associateBy { it.id }

        val entries = bundle.practitioners
            .filter { !requireMxid || it.mxid != null }
            .flatMap { practitioner ->
                val roleZip =
                    bundle.practitionerRoles.filter { it.practitionerReference == practitioner.id }.map { role ->
                        val organization = role.organizationReference?.let { organizationsById[it] }
                        role to practitionerConsolidator.getOrganizationChain(
                            organization, organizationsById, findOrganizationById::findById
                        )
                    }
                val groups = if (roleZip.isEmpty()) {
                    listOf(roleZip)
                } else {
                    roleZip.groupBy { (_, chain) -> chain.root()?.id }.values.toList()
                }
                groups.map { groupRoleZip ->
                    val root = groupRoleZip.firstNotNullOfOrNull { (_, chain) -> chain.root() }
                    val entry = mapOf(
                        "id" to practitioner.id,
                        "name" to practitionerConsolidator.buildDisplayName(practitioner, groupRoleZip),
                        "trustLevel" to practitioner.trustLevel.toString(),
                        "mxid" to practitioner.mxid?.value.orEmpty(),
                        "organizationId" to root?.id.orEmpty(),
                        "organizationName" to (root?.alias ?: root?.name).orEmpty()
                    )
                    matchesAllTerms(terms, practitioner, groupRoleZip) to entry
                }
            }

        return entries
            .filter { it.first }
            .sortedBy { it.second["name"].toString() }
            .map { it.second }
    }

    /**
     * Whether every [terms] term appears (case-insensitively) in the practitioner's name,
     * role descriptions or organization chain names/aliases.
     */
    private fun matchesAllTerms(
        terms: List<String>,
        practitioner: RawPractitioner,
        roleZip: List<Pair<RawPractitionerRole, OrgChain>>
    ): Boolean {
        val haystacks = listOf(practitioner.name) +
            roleZip.flatMap { (role, _) ->
                listOfNotNull(role.codeDisplay) + role.codes.map { it.display }
            } +
            roleZip.flatMap { (_, chain) ->
                chain.organizationChain.flatMap { listOfNotNull(it.name, it.alias) }
            }
        return terms.all { term -> haystacks.any { it.contains(term, ignoreCase = true) } }
    }

    /**
     * Searches the user directory for people and healthcare services visible to the current user.
     *
     * @param name the search term
     * @param currentUser the authenticated user resolving results
     * @return directory entries; empty if the current user cannot be consolidated
     */
    fun search(name: String, currentUser: Mxid): SearchResult {
        val currentPractitioner = practitionersMxidPort.search(currentUser)

        val consolidatedCurrentUser = if (currentPractitioner.practitioners.isNotEmpty()) {
            practitionerConsolidator.consolidate(currentPractitioner, currentUser)
        } else {
            val currentHealthcareService = healthcareServicesMxidPort.search(currentUser)
            healthcareServiceConsolidator.consolidate(currentHealthcareService, currentUser)
        } ?: return SearchResult(
            results = emptyList(),
            limited = false,
        )

        // phase 3: the current user must be allowed to send invitations under the org-wide policy
        val currentUserMaySend = userInvitationRightsPort.getPractitionerRights(currentUser)?.send != false
        if (!currentUserMaySend || blockedUserPort.isBlocked(currentUser) ||
            blockedRolePort.isAnyRoleBlocked(roleTokens(consolidatedCurrentUser), InvitationDirection.OUTBOUND)
        ) {
            return SearchResult(
                results = emptyList(),
                limited = false,
            )
        }

        // phase 4
        val practitionerSearchBundle = practitionersPort.search(name)
        val healthcareServicesBundle = healthcareServicesPort.searchHealthcareServices(name)

        // phase 5
        val practitionerMxids = practitionerSearchBundle.practitioners.mapNotNull { it.mxid }.distinct()
        val healthcareServicesMxids = healthcareServicesBundle.healthcareServices.mapNotNull { it.mxid }.distinct()

        // phase 6
        val consolidatedPeople =
            practitionerMxids.mapNotNull { practitionerConsolidator.consolidate(practitionerSearchBundle, it) }
        val consolidatedHealthcareServices = healthcareServicesMxids.mapNotNull {
            healthcareServiceConsolidator.consolidate(
                healthcareServicesBundle, it
            )
        }

        // phase 7: invitation policy (blocked mxids, blocked receive roles, disabled receive right)
        val filteredConsolidatedPeople = consolidatedPeople
            .filter { isPublicOrWithinSameOrganization(it, consolidatedCurrentUser) }
            .filterNot { isBlockedByOrganizationPolicy(it) }
        val filteredHealthCareServices = consolidatedHealthcareServices
            .filter { isPublicOrWithinSameOrganization(it, consolidatedCurrentUser) }
            .filterNot { isBlockedByOrganizationPolicy(it) }

        val terms = searchTermsOf(name)

        val matched = (filteredConsolidatedPeople + filteredHealthCareServices).map { user ->
            consolidatedMatchesAllTerms(terms, user) to user
        }
        val results = matched
            .filter { it.first }
            .sortedBy { it.second.displayName }
            .map { it.second.toEntry() }

        return SearchResult(
            limited = false,
            results = results,
        )
    }

    /**
     * Whether every [terms] term appears (case-insensitively) in the user's display name,
     * role descriptions or organization names/aliases.
     */
    private fun consolidatedMatchesAllTerms(terms: List<String>, user: ConsolidatedUser): Boolean {
        val searchableText = userSearchableText(user)
        return terms.all { term ->
            searchableText.any { text -> text.contains(term, ignoreCase = true) }
        }
    }

    private fun userSearchableText(user: ConsolidatedUser): List<String> {
        val roleText = user.roles.flatMap { role -> roleDescriptions(role) }
        val organizationText = user.organizations.flatMap { organization -> organizationNames(organization) }
        return listOf(user.displayName) + roleText + organizationText
    }

    private fun roleDescriptions(role: RawPractitionerRole): List<String> {
        val display = role.codeDisplay
        val codes = role.codes.map { it.display }
        return listOfNotNull(display) + codes
    }

    private fun organizationNames(organization: RawOrganization): List<String> =
        listOfNotNull(organization.name, organization.alias)

    private fun isBlockedByOrganizationPolicy(user: ConsolidatedUser): Boolean =
        blockedUserPort.isBlocked(user.mxid) ||
            blockedRolePort.isAnyRoleBlocked(roleTokens(user), InvitationDirection.INBOUND) ||
            userInvitationRightsPort.getPractitionerRights(user.mxid)?.receive == false

    private fun roleTokens(user: ConsolidatedUser): Set<String> =
        user.roles.flatMap { it.codes }.mapNotNull { code ->
            val system = code.system ?: return@mapNotNull null
            val value = code.code ?: return@mapNotNull null
            "$system|$value"
        }.toSet()

    /**
     * Whether a found user is visible to the current user: it is public or shares the same root organization.
     */
    private fun isPublicOrWithinSameOrganization(
        foundUser: ConsolidatedUser,
        currentUser: ConsolidatedUser
    ): Boolean =
        foundUser.visibility == Visibility.PUBLIC || foundUser.rootOrganization == currentUser.rootOrganization
}
