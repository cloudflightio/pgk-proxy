-- Per-user (personal) blacklist of roles scoped to an organization that may NOT send
-- this user room invitations. Absence of any row for an owner means all-allowed by default.
-- A row = the role (role catalog token "{system}|{code}") attached to the given organization
-- (FHIR Organization reference) is blocked for the owner. Rules apply globally (all federated
-- servers) and match the sender's practitioner roles (direct organization reference).
CREATE TABLE personal_invitation_blocks
(
    owner_mxid           TEXT NOT NULL,                  -- recipient mxid the rule applies to
    organization         TEXT NOT NULL,                  -- FHIR Organization reference the role is attached to
    role_token           TEXT NOT NULL,                  -- role catalog token "{system}|{code}"
    organization_display TEXT NOT NULL DEFAULT '',       -- cached org name for the UI list
    role_display         TEXT NOT NULL DEFAULT '',       -- cached role name for the UI list
    PRIMARY KEY (owner_mxid, organization, role_token)
);
