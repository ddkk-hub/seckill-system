-- Additive migration; never assigns legacy client-supplied user IDs to new accounts.
CREATE TABLE IF NOT EXISTS seckill_user (
    id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username(username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
