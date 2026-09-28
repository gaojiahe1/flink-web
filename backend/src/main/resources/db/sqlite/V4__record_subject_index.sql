-- V4：按作业/SQL 标识检索历史，避免读取整个 JSON 集合。
CREATE TABLE IF NOT EXISTS platform_record_subjects (
    collection VARCHAR(40) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY(collection,record_id)
);
CREATE INDEX IF NOT EXISTS idx_record_subject ON platform_record_subjects(collection,subject_id,created_at,record_id);
INSERT INTO platform_schema(version) SELECT 4 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=4);
