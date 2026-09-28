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
