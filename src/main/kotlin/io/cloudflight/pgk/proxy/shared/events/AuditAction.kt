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

enum class AuditAction(val value: String) {

    FhirClientInit("fhir.client.init"),
    FhirSearchAdapterActive("fhir.search-adapter.active"),

    FederationWhitelistRefresh("federation.whitelist.refresh"),
    FederationWhitelistRefreshFailed("federation.whitelist.refresh.failed"),
    FederationBlocked("federation.blocked"),
    SquidWhitelistUpdate("squid.whitelist.update"),
    SquidWhitelistUpdateFailed("squid.whitelist.update.failed"),
    MatrixWhoamiFailed("matrix.whoami.failed"),

    InvitationAllowed("invitation.allowed"),
    InvitationBlocked("invitation.blocked"),
    InvitationForwarded("invitation.forwarded"),
    InvitationForwardFailed("invitation.forward.failed"),

    TruststoreCaMissing("truststore.ca.missing"),
    TruststoreCaTrusted("truststore.ca.trusted"),

    IdpProvisionRemove("idp.provision.remove"),
    IdpProvisionSkip("idp.provision.skip"),
    IdpProvisionWrite("idp.provision.write"),
    IdpUserEventRejected("idp.user-event.rejected"),

    InvitationRightsChangeEmpty("invitation-rights.change.empty"),
    InvitationRightsChangeCreated("invitation-rights.change.created"),
    InvitationRightsChangeAllowed("invitation-rights.change.allowed"),
    InvitationRightsChangeRemoved("invitation-rights.change.removed"),
    InvitationRightsChangeUpdated("invitation-rights.change.updated"),
    InvitationRightsRolesSave("invitation-rights.roles.save"),
    InvitationRightsRolesToggle("invitation-rights.roles.toggle"),
    InvitationRightsUserBlock("invitation-rights.user.block"),
    InvitationRightsUserUnblock("invitation-rights.user.unblock"),
    InvitationRightsToggle("invitation-rights.toggle"),
    InvitationRightsPractitionersLoad("invitation-rights.practitioners.load"),
    InvitationRightsBlockedUsersLoad("invitation-rights.blocked-users.load"),
    InvitationRightsBlockedRolesLoad("invitation-rights.blocked-roles.load"),
    InvitationRightsDialogRolesLoad("invitation-rights.dialog-roles.load"),
    InvitationRightsSettingUpdate("invitation-rights.setting.update"),

    InvitationRulesSave("invitation-rules.save"),
    InvitationRulesRemove("invitation-rules.remove"),
    InvitationRulesUserBlock("invitation-rules.user.block"),
    InvitationRulesUserUnblock("invitation-rules.user.unblock"),
    InvitationRulesBlockedUsersLoad("invitation-rules.blocked-users.load"),

    SearchPractitionerNotFound("search.practitioner.not-found"),
    SearchPractitioners("search.practitioners"),
    SearchOrganizations("search.organizations"),
    SearchRoles("search.roles"),
    SearchPractitionerOrganization("search.practitioner.organization"),
    SearchPractitionerRoles("search.practitioner.roles"),
    SearchUsers("search.users"),
    SearchConsolidateHealthcareService("search.consolidate.healthcare-service"),
    SearchConsolidatePractitioner("search.consolidate.practitioner"),
    DisplayNameCompute("display-name.compute"),

    VisibilitySet("visibility.set"),
    VisibilitySetReadBackMismatch("visibility.set.read-back-mismatch"),
    VisibilityLoadFailed("visibility.load.failed"),

    HttpUnhandledError("http.unhandled-error"),
    RolesApply("roles.apply"),
    SystemReady("system.ready"),

    RoleCatalogReloaded("role-catalog.reloaded"),
    RoleCatalogEmpty("role-catalog.empty"),
    RoleCatalogLoadFailed("role-catalog.load.failed"),
    RoleAssignmentSaveFailed("role-assignment.save.failed"),
    RoleRemovalFailed("role-removal.failed"),
    ValuesetLoaded("valueset.loaded"),
    ValuesetSkipped("valueset.skipped"),
    ValuesetScanFailed("valueset.scan.failed"),
    ValuesetParseFailed("valueset.parse.failed"),
    OrganizationCreated("organization.created"),
    OrganizationCreateFailed("organization.create.failed"),
    OrganizationUpdated("organization.updated"),
    OrganizationUpdateFailed("organization.update.failed"),
    OrganizationDeleted("organization.deleted"),
    OrganizationDeleteFailed("organization.delete.failed"),
    OrganizationTreeLoadFailed("organization.tree.load.failed"),
    OrganizationSearchFailed("organization.search.failed"),
    AdministratorOrganizationResolveFailed("administrator.organization.resolve.failed"),
    PractitionerListLoadFailed("practitioner.list.load.failed"),
    PractitionerOrganizationResolveFailed("practitioner.organization.resolve.failed"),
    PractitionerRolesLoadFailed("practitioner.roles.load.failed")
}
