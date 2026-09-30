-- 作业编号永久保留，已有作业保留原资源名。
CREATE TABLE IF NOT EXISTS job_numbers (
 job_number INTEGER PRIMARY KEY AUTOINCREMENT,
 job_id VARCHAR(36) NOT NULL UNIQUE,
 deployment_name VARCHAR(63) UNIQUE
);
INSERT INTO job_numbers(job_id,deployment_name)
SELECT id,name FROM job_drafts WHERE id NOT IN (SELECT job_id FROM job_numbers) ORDER BY created_at,id;
INSERT INTO platform_schema(version) SELECT 5 WHERE NOT EXISTS (SELECT 1 FROM platform_schema WHERE version=5);
