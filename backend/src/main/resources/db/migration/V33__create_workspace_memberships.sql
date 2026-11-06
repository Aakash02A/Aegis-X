CREATE TABLE workspace_members (
    id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id     CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workspace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `role`      VARCHAR(32) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_workspace_members_user_workspace UNIQUE (user_id, workspace_id),
    CONSTRAINT fk_workspace_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_members_workspace FOREIGN KEY (workspace_id)
        REFERENCES pi_workspaces(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO workspace_members (id, user_id, workspace_id, `role`)
SELECT UUID(), users.id, pi_workspaces.id, 'OWNER'
FROM users
CROSS JOIN pi_workspaces
WHERE users.`role` = 'ADMIN';
