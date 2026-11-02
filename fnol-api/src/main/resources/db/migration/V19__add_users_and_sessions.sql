-- People sign in with their own account and role. Passwords are salted hashes; session tokens are stored only as hashes.
CREATE TABLE app_users (
    username      VARCHAR(40)  PRIMARY KEY,
    display_name  VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    active        BOOLEAN      NOT NULL
);

CREATE TABLE user_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    username   VARCHAR(40) NOT NULL REFERENCES app_users (username),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_user_sessions_username ON user_sessions (username);
