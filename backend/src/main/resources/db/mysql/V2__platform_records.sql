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
