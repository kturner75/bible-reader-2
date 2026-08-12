-- H1: track whether the account email has been proven (Google claim or future
-- verify-email). Existing rows are grandfathered as verified so we don't lock
-- out production users; new password registrations start unverified.
ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
