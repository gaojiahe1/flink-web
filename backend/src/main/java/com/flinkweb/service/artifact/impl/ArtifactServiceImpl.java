package com.flinkweb.service.artifact.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.service.artifact.ArtifactService;
import com.flinkweb.service.catalog.CatalogService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 编排 S3 制品存储和平台目录登记，对控制层隐藏存储客户端。 */
@Service
public class ArtifactServiceImpl implements ArtifactService {
  private final S3StorageClient client;
  private final CatalogService catalogs;

  /** 注入对象存储客户端和制品目录服务。 */
  public ArtifactServiceImpl(S3StorageClient client, CatalogService catalogs) {
    this.client = client;
    this.catalogs = catalogs;
  }

  /** 委托 S3 客户端查询目录及下一页。 */
  public ObjectNode list(String prefix, String token) {
    return client.list(prefix, token);
  }

  /** 上传制品后登记其地址、校验和和文件名。 */
  public ObjectNode upload(MultipartFile file, String category) {
    var artifact = client.upload(file, category);
    return catalogs.save("artifacts", artifact.path("id").asText(), artifact);
  }

  /** 委托 S3 客户端提供已校验范围的对象流。 */
  public ResponseInputStream<GetObjectResponse> download(String key) {
    return client.download(key);
  }
}
