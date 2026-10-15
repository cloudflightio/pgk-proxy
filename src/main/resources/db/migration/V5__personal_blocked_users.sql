-- Per-user (personal) blacklist of Matrix users. A stored mxid may not send this owner
-- (recipient) room invitations over federation. Absence of any row for an owner means
-- all-allowed by default. A row = the sender mxid that is blocked for the owner.
CREATE TABLE personal_blocked_users
(
    owner_mxid   TEXT NOT NULL,                  -- recipient mxid the block applies to
    blocked_mxid TEXT NOT NULL,                  -- sender mxid that is blocked for the owner
    PRIMARY KEY (owner_mxid, blocked_mxid)
);
