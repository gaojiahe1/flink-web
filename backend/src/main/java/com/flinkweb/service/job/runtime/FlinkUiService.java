package com.flinkweb.service.job.runtime;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.http.*;

/** 服务契约：校验登录作业访问并转发 Flink UI 只读请求，限制路径及脱敏配置内容。 */
public interface FlinkUiService {
  /** 重定向到带尾斜杠的 UI 地址，确保相对资源能从代理路径加载。 */
  void trailingSlash(String id, HttpServletResponse response) throws IOException;

  /** 校验作业和请求路径，向集群内 REST Service 转发只读请求并处理配置脱敏。 */
  void proxy(String id, HttpServletRequest request, HttpServletResponse response)
      throws IOException;
}
