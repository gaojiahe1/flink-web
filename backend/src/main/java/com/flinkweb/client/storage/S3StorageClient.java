package com.flinkweb.client.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

/** 封装 S3 目录查询、流式上传和下载；元数据登记由业务服务负责。 */
@Component
public class S3StorageClient implements AutoCloseable {
  private final String bucket, prefix;
  private final String stateBucket;
  private final String endpoint, region, stateRoot;
  private final ObjectMapper mapper;
  private final S3Client client;

  /** 根据端点、区域和默认凭据链创建 S3 客户端；未配置 bucket 时保持禁用。 */
  public S3StorageClient(
      ObjectMapper mapper,
      @Value("${platform.s3.bucket:}") String bucket,
      @Value("${platform.s3.prefix:artifacts/}") String prefix,
      @Value("${platform.s3.endpoint:}") String endpoint,
      @Value("${platform.s3.region:us-east-1}") String region,
      @Value("${platform.workload.state-root:s3://flink-state}") String stateRoot) {
    this.mapper = mapper;
    this.bucket = bucket;
    this.prefix = prefix.replaceAll("/+$", "") + "/";
    this.endpoint = endpoint;
    this.region = region;
    this.stateRoot = stateRoot;
    URI stateUri = URI.create(stateRoot);
    this.stateBucket = "s3".equals(stateUri.getScheme()) ? stateUri.getHost() : null;
    var builder =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(java.time.Duration.ofMinutes(5))
                        .apiCallAttemptTimeout(java.time.Duration.ofMinutes(2)));
    if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
    this.client = bucket.isBlank() ? null : builder.build();
  }

  /** 检查 S3 客户端是否配置，避免在缺少存储参数时继续执行。 */
  private void configured() {
    if (client == null)
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "S3_UNCONFIGURED", "请配置 S3 bucket、endpoint 和 AWS 凭证");
  }

  /** 验证目录位于允许前缀内，拒绝目录穿越和反斜杠。 */
  private String safePrefix(String requested) {
    String value = requested == null || requested.isBlank() ? prefix : requested;
    if (!value.startsWith(prefix) || value.contains("..") || value.contains("\\"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PREFIX", "目录超出允许范围");
    return value;
  }

  /** 按前缀分页列举对象和子目录，返回下一页令牌。 */
  public ObjectNode list(String requested, String token) {
    configured();
    try {
      var builder =
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .prefix(safePrefix(requested))
              .delimiter("/")
              .maxKeys(200);
      if (token != null && !token.isBlank()) builder.continuationToken(token);
      var response = client.listObjectsV2(builder.build());
      var result =
          mapper.createObjectNode().put("bucket", bucket).put("prefix", safePrefix(requested)).put("rootPrefix", prefix);
      var dirs = result.putArray("directories");
      for (var dir : response.commonPrefixes()) dirs.add(dir.prefix());
      var objects = result.putArray("objects");
      for (var object : response.contents())
        objects
            .addObject()
            .put("key", object.key())
            .put("size", object.size())
            .put("modifiedAt", object.lastModified().toString());
      result.put("nextToken", response.nextContinuationToken());
      return result;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  /** 校验上传文件、计算 SHA-256 并写入 S3，返回待登记的制品信息。 */
  public ObjectNode upload(MultipartFile file) {
    return upload(file, "shared");
  }

  /** 按主程序、依赖和公共目录上传，使用随机标识避免同名覆盖。 */
  public ObjectNode upload(MultipartFile file, String category) {
    boolean direct = category.startsWith(prefix);
    if (!direct && !Set.of("main", "dependency", "shared").contains(category))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY", "制品目录类型无效");
    configured();
    String filename = file.getOriginalFilename();
    if (filename == null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}") || file.isEmpty())
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_FILE", "请选择非空文件，文件名仅允许字母、数字、点、下划线和连字符");
    if (category.equals("main") && !filename.toLowerCase(Locale.ROOT).endsWith(".jar"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "主程序必须为 JAR 文件");
    Path temporary = null;
    try {
      temporary = Files.createTempFile("flink-web-upload-", ".bin");
      file.transferTo(temporary);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = new DigestInputStream(Files.newInputStream(temporary), digest)) {
        stream.transferTo(java.io.OutputStream.nullOutputStream());
      }
      String checksum = HexFormat.of().formatHex(digest.digest()),
          id = UUID.randomUUID().toString(),
          key = direct ? safePrefix(category).replaceAll("/+$", "") + "/" + filename
              : prefix + category + "/" + id + "/" + filename;
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("application/octet-stream")
              .metadata(Map.of("sha256", checksum))
              .overrideConfiguration(c -> c.putHeader("If-None-Match", "*"))
              .build(),
          RequestBody.fromFile(temporary));
      var record =
          mapper
              .createObjectNode()
              .put("category", category)
              .put("name", filename)
              .put("filename", filename)
              .put("sha256", checksum)
              .put("uri", "s3://" + bucket + "/" + key);
      return record.put("id", "s3:" + key).put("key", key);
    } catch (BusinessException ex) {
      throw ex;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 412 || ex.statusCode() == 409)
        throw new BusinessException(HttpStatus.CONFLICT, "OBJECT_EXISTS", "同名文件已存在，请更换文件名或先删除旧文件");
      throw unavailable();
    } catch (Exception ex) {
      throw unavailable();
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (Exception ignored) {
        }
    }
  }

  /** 返回可公开的连接默认值，不返回任何凭证。 */
  public ObjectNode defaults() {
    return mapper.createObjectNode().put("bucket", bucket).put("rootPrefix", prefix)
        .put("endpoint", endpoint).put("region", region).put("stateRoot", stateRoot);
  }

  /** 读取选中对象并计算真实校验和，支持直接上传到固定目录的文件。 */
  public ObjectNode describe(String key) {
    configured();
    safePrefix(key);
    if (key.endsWith("/")) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "请选择文件");
    try {
      var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      if (head.contentLength() != null && head.contentLength() > 1024L * 1024 * 1024)
        throw new BusinessException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "单个依赖文件不能超过下载器的 1 GiB 限制");
      String checksum = head.metadata().getOrDefault("sha256", "");
      if (!checksum.matches("[a-fA-F0-9]{64}")) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(download(key), digest)) {
          input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        checksum = HexFormat.of().formatHex(digest.digest());
      }
      return mapper.createObjectNode().put("id", "s3:" + key).put("key", key)
          .put("filename", key.substring(key.lastIndexOf('/') + 1))
          .put("uri", "s3://" + bucket + "/" + key).put("sha256", checksum);
    } catch (BusinessException ex) { throw ex; }
      catch (Exception ex) { throw unavailable(); }
  }

  /** 创建目录标记，不覆盖已经存在的对象。 */
  public ObjectNode mkdir(String requested) {
    configured();
    String key = safePrefix(requested).replaceAll("/+$", "") + "/";
    if (key.equals(prefix)) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PREFIX", "请输入子目录");
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
          .overrideConfiguration(c -> c.putHeader("If-None-Match", "*")).build(), RequestBody.empty());
      return mapper.createObjectNode().put("key", key);
    } catch (Exception ex) { throw unavailable(); }
  }

  /** 仅删除明确指定的单个文件；不执行递归目录删除。 */
  public ObjectNode delete(String key) {
    configured();
    safePrefix(key);
    if (key.endsWith("/")) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "请进入目录后选择具体文件，不能递归删除目录");
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
      return mapper.createObjectNode().put("deleted", true).put("key", key);
    } catch (Exception ex) { throw unavailable(); }
  }

  /** 校验对象范围后获取下载流，由调用方关闭。 */
  public software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> download(String key) {
    configured();
    safePrefix(key);
    try {
      return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  /** 生成统一的 S3 访问失败异常。 */
  private BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY, "S3_ERROR", "S3 请求失败，请检查端点、凭证、目录权限和对象是否存在");
  }

  /** 检查同一受管 bucket 内快照的元数据文件；不扫描目录，也不承诺状态兼容。 */
  public ObjectNode checkSnapshot(String path) {
    configured();
    URI uri;
    try {
      uri = URI.create(path);
    } catch (Exception ex) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SNAPSHOT", "快照路径无效");
    }
    if (!"s3".equals(uri.getScheme())
        || !(bucket.equals(uri.getHost()) || Objects.equals(stateBucket, uri.getHost()))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPort() != -1)
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "SNAPSHOT_BUCKET", "只允许检查制品桶或配置的状态目录桶中的快照");
    String key = uri.getPath().replaceFirst("^/", "");
    if (key.isBlank() || key.contains("..") || key.contains("\\"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SNAPSHOT", "快照路径无效");
    String metadata =
        (key.equals("_metadata") || key.endsWith("/_metadata"))
            ? key
            : key.replaceAll("/+$", "") + "/_metadata";
    boolean exists;
    try {
      client.headObject(
          HeadObjectRequest.builder()
              .bucket(uri.getHost())
              .key(metadata)
              .overrideConfiguration(
                  c ->
                      c.apiCallTimeout(java.time.Duration.ofSeconds(10))
                          .apiCallAttemptTimeout(java.time.Duration.ofSeconds(5)))
              .build());
      exists = true;
    } catch (S3Exception ex) {
      if (ex.statusCode() == 404) exists = false;
      else throw unavailable();
    } catch (Exception ex) {
      throw unavailable();
    }
    return mapper
        .createObjectNode()
        .put("exists", exists)
        .put("checkedAt", java.time.Instant.now().toString())
        .put("path", path)
        .put("message", exists ? "元数据文件存在；还需确认共享状态文件、运行版本与算子状态兼容" : "未找到快照元数据文件");
  }

  /** 关闭 S3 客户端并释放底层连接。 */
  @Override
  public void close() {
    if (client != null) client.close();
  }
}
