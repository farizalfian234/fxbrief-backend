CREATE TABLE roles (
    id   SMALLINT     PRIMARY KEY,
    name VARCHAR(50)  NOT NULL UNIQUE
);

CREATE TABLE users (
    id                     BIGSERIAL    PRIMARY KEY,
    email                  VARCHAR(255) NOT NULL UNIQUE,
    password_hash          VARCHAR(255),
    name                   VARCHAR(255),
    timezone               VARCHAR(64),
    role_id                SMALLINT     NOT NULL REFERENCES roles(id),
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    has_ever_paid          BOOLEAN      NOT NULL DEFAULT FALSE,
    deletion_requested_at  TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_role_id ON users (role_id);

INSERT INTO roles (id, name) VALUES
    (1, 'USER'),
    (2, 'ADMIN');
