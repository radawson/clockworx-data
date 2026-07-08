-- Minimal cotr schema for baseline adoption smoke test
CREATE TABLE IF NOT EXISTS ${tablePrefix}banks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    owner_uuid VARCHAR(36) NOT NULL,
    world_name VARCHAR(255),
    balance DECIMAL(20, 2) NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
