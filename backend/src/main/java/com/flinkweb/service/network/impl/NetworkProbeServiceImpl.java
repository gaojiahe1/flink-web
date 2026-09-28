package com.flinkweb.service.network.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.network.NetworkProbeService;
import java.net.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 手动 TCP 端口验证：限制并发和整体等待时间，不发送应用数据。 */
@Service
public class NetworkProbeServiceImpl implements NetworkProbeService {
  private final ObjectMapper mapper;
  private final Semaphore slots = new Semaphore(4);
  private final ExecutorService workers =
      Executors.newFixedThreadPool(
          4,
          task -> {
            Thread thread = new Thread(task, "network-probe");
            thread.setDaemon(true);
            return thread;
          });

  /** 注入 JSON 工具，诊断不持久化连接状态。 */
  public NetworkProbeServiceImpl(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 验证端口范围、执行 DNS/TCP 检查，并区分连通性与认证结果。 */
  public ObjectNode test(String host, int port) {
    if (host == null
        || host.length() > 253
        || !host.matches("[A-Za-z0-9][A-Za-z0-9.:-]*")
        || port < 1
        || port > 65535)
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_ENDPOINT", "主机不能包含协议或路径，端口必须为 1–65535 的整数");
    if (!slots.tryAcquire())
      throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "PROBE_BUSY", "网络测试繁忙，请稍后重试");
    long start = System.nanoTime();
    Future<Boolean> future =
        workers.submit(
            () -> {
              try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 3000);
                return true;
              } finally {
                slots.release();
              }
            });
    boolean connected = false;
    try {
      connected = future.get(4, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      future.cancel(true);
    } catch (Exception ex) {
      future.cancel(true);
    }
    return mapper
        .createObjectNode()
        .put("level", "TCP")
        .put("connected", connected)
        .put("host", host)
        .put("port", port)
        .put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .put("checkedAt", java.time.Instant.now().toString())
        .put("message", connected ? "TCP 端口可达；尚未验证账号、协议或 Flink 容器网络" : "TCP 连接失败或超时，请检查主机、端口及网络策略");
  }

  /** 应用退出时释放诊断线程。 */
  @jakarta.annotation.PreDestroy
  public void close() {
    workers.shutdownNow();
  }
}
