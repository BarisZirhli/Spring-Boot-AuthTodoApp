CREATE TABLE IF NOT EXISTS todos (
    id             BIGSERIAL PRIMARY KEY,
    version        BIGINT                   NOT NULL DEFAULT 0,
    title          VARCHAR(120)             NOT NULL,
    description    VARCHAR(1000),
    completed      BOOLEAN                  NOT NULL DEFAULT FALSE,
    deleted_at     TIMESTAMP WITH TIME ZONE,
    deleted_by     VARCHAR(50),
    owner_username VARCHAR(50)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_todos_owner_username ON todos (owner_username);