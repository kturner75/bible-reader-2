-- ============================================================
-- V12: persistent_logins — backing store for Spring Security's
-- remember-me feature (PersistentTokenBasedRememberMeServices)
-- ============================================================

CREATE TABLE persistent_logins (
    username  VARCHAR(254) NOT NULL, -- matches users.email (VARCHAR(254)), the username Spring Security stores here
    series    VARCHAR(64)  PRIMARY KEY,
    token     VARCHAR(64)  NOT NULL,
    last_used TIMESTAMP    NOT NULL
);
