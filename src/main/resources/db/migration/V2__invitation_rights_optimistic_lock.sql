-- Optimistic locking for the per-practitioner toggles: concurrent updates to the same
-- practitioner row now fail with an optimistic-lock exception instead of silently
-- overwriting each other (read-modify-write in JpaInvitationRightsAdapter).
ALTER TABLE practitioner_invitation_rights
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
