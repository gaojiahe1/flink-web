package com.flinkweb.service.system.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.system.DownloaderSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 以固定记录主键确保整个平台只有一个下载器镜像配置。 */
@Service
public class DownloaderSettingsServiceImpl implements DownloaderSettingsService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final AuditService audit;
  private final String fallback;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DownloaderSettingsServiceImpl.class);

  /** 注入元数据与兼容环境变量，仅作为未保存设置时的初始值。 */
  public DownloaderSettingsServiceImpl(
      RecordRepository records,
      ObjectMapper mapper,
      AuditService audit,
      @Value("${platform.artifacts.downloader-image:}") String fallback) {
    this.records = records;
    this.mapper = mapper;
    this.audit = audit;
    this.fallback = fallback.trim();
  }

  /** 返回单一生效地址，不读取 Flink 镜像记录中的历史下载器字段。 */
  public ObjectNode get() {
    var record = records.find("settings", "downloader");
    return record == null
        ? mapper.createObjectNode().put("image", fallback).put("revision", 0)
        : record;
  }

  /** 校验单一镜像地址并以乐观锁保存绑定，记录不含密钥的审计信息。 */
  public ObjectNode save(ObjectNode input) {
    String image = input.path("image").asText().trim();
    if (image.isBlank()
        || image.length() > 512
        || !image.matches("[a-zA-Z0-9][a-zA-Z0-9.:/_@-]*")
        || image.contains("://")
        || image.contains(".."))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "image: 请填写有效的单个下载器镜像地址");
    if (!input.path("revision").isIntegralNumber() || input.path("revision").asLong() < 0)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REVISION", "缺少有效修订号，请刷新");
    var saved =
        records.save(
            "settings",
            "downloader",
            mapper.createObjectNode().put("image", image),
            input.path("revision").asLong());
    audit.record("DOWNLOADER_IMAGE_UPDATE", "downloader", "SUCCESS");
    log.info("Downloader binding updated revision={}", saved.path("revision").asLong());
    return saved;
  }

  /** 下载器未配置时中止生成，避免提交错误的默认镜像。 */
  public String requireImage() {
    String image = get().path("image").asText();
    if (image.isBlank())
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "DOWNLOADER_IMAGE_REQUIRED", "downloaderImage: 请在系统设置中绑定文件下载器镜像");
    return image;
  }
}
