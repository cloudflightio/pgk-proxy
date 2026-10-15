-- Invitation-rights persistence for the pgk proxy.
-- Rights are stored in SQL (not FHIR meta tags); FHIR stays a read-only directory.

-- Per-practitioner receive/send toggles. Absent row = allowed (receive=true, send=true).
-- practitioner_id stores the bare FHIR Practitioner idPart (e.g. '1'); no FK, the
-- directory itself lives in FHIR.
CREATE TABLE practitioner_invitation_rights
(
    practitioner_id TEXT PRIMARY KEY, -- bare FHIR Practitioner idPart; no FK, directory lives in FHIR
    receive         BOOLEAN NOT NULL DEFAULT TRUE,
    send            BOOLEAN NOT NULL DEFAULT TRUE
);

-- Per-role receive/send block lists. Empty table = nothing blocked (All Roles); a row
-- = that role is blocked for the direction. Values use the UI terms 'receive'/'send'.
CREATE TABLE org_role_invitation_blocks
(
    direction  VARCHAR(10) NOT NULL CHECK (direction IN ('receive', 'send')),
    role_token TEXT        NOT NULL, -- RoleCatalogEntry.token "{roleSystem}|{roleCode}"
    PRIMARY KEY (direction, role_token)
);
