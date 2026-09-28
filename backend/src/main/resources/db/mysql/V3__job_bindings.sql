-- V3：已有 FlinkDeployment 的身份绑定，纳管不修改集群资源。
CREATE TABLE IF NOT EXISTS job_bindings (
    job_id VARCHAR(36) NOT NULL PRIMARY KEY,
    namespace VARCHAR(63) NOT NULL,
    resource_name VARCHAR(63) NOT NULL,
    resource_uid VARCHAR(128) NOT NULL, -- Kubernetes UID；同名重建后不自动继承管理权
    resource_version VARCHAR(128) NOT NULL, -- 纳管时确认的资源版本
    imported_at VARCHAR(40) NOT NULL,
    CONSTRAINT uq_binding_name UNIQUE(namespace,resource_name),
    CONSTRAINT uq_binding_uid UNIQUE(namespace,resource_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO platform_schema(version) SELECT 3 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=3);
