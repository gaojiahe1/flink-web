package com.flinkweb.service.artifact;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 服务契约：编排 S3 制品存储和平台目录登记，对控制层隐藏存储客户端。 */
public interface ArtifactService {
  /** 委托 S3 客户端查询目录及下一页。 */
  ObjectNode list(String prefix, String token);

  /** 上传制品后登记其地址、校验和和文件名。 */
  ObjectNode upload(MultipartFile file, String category);

  /** 委托 S3 客户端提供已校验范围的对象流。 */
  ResponseInputStream<GetObjectResponse> download(String key);
}
