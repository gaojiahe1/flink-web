package com.flinkweb.service.artifact;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 服务契约：提供直接 S3 对象操作，对控制层隐藏存储客户端。 */
public interface ArtifactService {
  /** 委托 S3 客户端查询目录及下一页。 */
  ObjectNode list(String prefix, String token);

  /** 上传文件后返回地址、校验和和文件名。 */
  ObjectNode upload(MultipartFile file, String category);

  /** 返回非敏感的对象存储和状态目录默认值。 */
  ObjectNode defaults();

  /** 在允许前缀中创建子目录标记。 */
  ObjectNode mkdir(String prefix);

  /** 删除一个明确选中的对象，不递归删除目录。 */
  ObjectNode delete(String key);

  /** 删除明确选中的非根目录及其全部当前对象。 */
  ObjectNode deleteDirectory(String prefix);

  /** 委托 S3 客户端提供已校验范围的对象流。 */
  ResponseInputStream<GetObjectResponse> download(String key);
}
