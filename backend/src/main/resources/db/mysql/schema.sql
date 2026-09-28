-- MySQL 8+ 完整建表 DDL；重复执行不删除业务数据。
-- 增量版本与应用启动迁移使用相同 SQL。
-- V1：平台版本表和作业元数据；时间统一保存为 UTC ISO-8601 字符串。
CREATE TABLE IF NOT EXISTS platform_schema (
    version INTEGER NOT NULL PRIMARY KEY -- 已完成的数据库迁移版本
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS job_drafts (
    id VARCHAR(36) NOT NULL PRIMARY KEY, -- 平台生成的作业 ID
    name VARCHAR(63) NOT NULL UNIQUE, -- 固定 namespace 内唯一的作业名称
    description VARCHAR(500) NOT NULL, -- 作业用途说明
    flink_version VARCHAR(20) NOT NULL, -- Flink 运行时版本
    yaml_text LONGTEXT NOT NULL, -- 草稿 YAML，纳管时保留原始 spec
    revision BIGINT NOT NULL, -- 乐观锁，草稿初始版本为 0
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO platform_schema(version) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=1);

-- V2：平台目录、用户、审计、幂等请求等元数据。
-- collection 取值：users/templates/images/configs/artifacts/audit/operations/system/sql_jobs/datasources。
-- payload 是 JSON 文本；密钥保存 encryptedValue，不保存解密后的值。
CREATE TABLE IF NOT EXISTS platform_records (
    collection VARCHAR(40) NOT NULL,
    id VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    revision BIGINT NOT NULL, -- 通用记录初始版本为 1
    PRIMARY KEY (collection,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO platform_schema(version) SELECT 2 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=2);

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

-- V4：使用普通列关联历史主体，不依赖数据库特定 JSON 函数。
CREATE TABLE IF NOT EXISTS platform_record_subjects (
    collection VARCHAR(40) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(collection,record_id),
    INDEX idx_record_subject(collection,subject_id,created_at,record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO platform_schema(version) SELECT 4 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=4);
