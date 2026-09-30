package com.flinkweb.service.artifact.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.service.artifact.ArtifactService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 提供直接 S3 对象操作，对控制层隐藏存储客户端。 */
@Service
public class ArtifactServiceImpl implements ArtifactService {
  private final S3StorageClient client;

  /** 注入对象存储客户端。 */
  public ArtifactServiceImpl(S3StorageClient client) {
    this.client = client;
  }

  /** 委托 S3 客户端查询目录及下一页。 */
  public ObjectNode list(String prefix, String token) {
    return client.list(prefix, token);
  }

  /** 上传文件后直接返回地址、校验和和文件名。 */
  public ObjectNode upload(MultipartFile file, String category) {
    var artifact = client.upload(file, category);
    return artifact;
  }

  /** 返回非敏感连接默认值。 */
  public ObjectNode defaults() { return client.defaults(); }

  /** 创建目录标记。 */
  public ObjectNode mkdir(String prefix) { return client.mkdir(prefix); }

  /** 删除单个对象。 */
  public ObjectNode delete(String key) { return client.delete(key); }

  /** 委托 S3 客户端提供已校验范围的对象流。 */
  public ResponseInputStream<GetObjectResponse> download(String key) {
    return client.download(key);
  }
}
