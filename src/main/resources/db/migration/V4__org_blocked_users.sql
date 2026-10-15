-- Organization-wide blocked Matrix users. A listed mxid may neither send nor receive
-- federated room invitations within the managed domain (blocked for both directions).
-- Absence of a row = allowed (default allow).
CREATE TABLE org_blocked_users
(
    mxid TEXT PRIMARY KEY
);
