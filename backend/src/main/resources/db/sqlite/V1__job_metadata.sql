-- V1：平台版本表和作业元数据；时间统一保存为 UTC ISO-8601 字符串。
CREATE TABLE IF NOT EXISTS platform_schema (
    version INTEGER NOT NULL PRIMARY KEY -- 已完成的数据库迁移版本
);
CREATE TABLE IF NOT EXISTS job_drafts (
    id VARCHAR(36) NOT NULL PRIMARY KEY, -- 平台生成的作业 ID
    name VARCHAR(63) NOT NULL UNIQUE, -- 固定 namespace 内唯一的作业名称
    description VARCHAR(500) NOT NULL, -- 作业用途说明
    flink_version VARCHAR(20) NOT NULL, -- Flink 运行时版本
    yaml_text TEXT NOT NULL, -- 草稿 YAML，纳管时保留原始 spec
    revision BIGINT NOT NULL, -- 乐观锁，草稿初始版本为 0
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);
INSERT INTO platform_schema(version) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=1);
