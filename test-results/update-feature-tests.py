from pathlib import Path
root = Path(__file__).resolve().parents[1]
p = root / 'backend/src/test/java/com/flinkweb/S3PluginPodTest.java'
s = p.read_text(encoding='utf-8').replace('            templates,\n', '            templates,\n            mock(com.flinkweb.service.artifact.support.ArtifactReferences.class),\n')
s = s.replace('旧独立插件容器消失，显式依赖中的插件迁入插件卷，其他文件保持原位置。', '旧独立插件容器消失，用户显式依赖保留在下载清单与共享文件卷。')
s = s.replace('assertThat(manifest).hasSize(1);', 'assertThat(manifest).hasSize(2);')
p.write_text(s,encoding='utf-8')
p = root / 'backend/src/test/java/com/flinkweb/ValidationJarBundleTest.java'
s = p.read_text(encoding='utf-8').replace('new ValidationJarBundle(storage, mock(RecordRepository.class))', 'new ValidationJarBundle(storage, mock(RecordRepository.class), new com.flinkweb.service.artifact.support.ArtifactReferences(storage, mock(RecordRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(), ""))')
s = s.replace('new ValidationJarBundle(mock(S3StorageClient.class), records)', 'new ValidationJarBundle(mock(S3StorageClient.class), records, new com.flinkweb.service.artifact.support.ArtifactReferences(mock(S3StorageClient.class), records, new com.fasterxml.jackson.databind.ObjectMapper(), ""))')
s = s.replace('hasMessageContaining("平台 S3")', 'hasMessageContaining("下载白名单")')
p.write_text(s,encoding='utf-8')
p = root / 'backend/src/test/java/com/flinkweb/PlatformIntegrationTest.java'
s = p.read_text(encoding='utf-8').replace('验证敏感配置密文持久化，部署中仅通过 Secret 引用使用。','验证敏感配置密文持久化，提交时展开实际值且预览保持引用。').replace('secretIsEncryptedAndOnlyBecomesSecretRef','secretIsEncryptedAndExpandedOnlyAtSubmission')
s = s.replace('assertThat(rendered.deployment().toString())\n        .doesNotContain("super-sensitive-value")\n        .contains("secretKeyRef");\n    assertThat(rendered.secrets()).hasSize(1);', 'assertThat(rendered.deployment().toString()).contains("super-sensitive-value").doesNotContain("secretKeyRef");\n    assertThat(rendered.secrets()).isEmpty();\n    assertThat(renderer.preview(rendered.deployment()).toString()).doesNotContain("super-sensitive-value").contains("{{secret:" + id + "}}");')
p.write_text(s,encoding='utf-8')
