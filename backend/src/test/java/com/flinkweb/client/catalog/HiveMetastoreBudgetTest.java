package com.flinkweb.client.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.exception.common.BusinessException;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 用单调时钟和受控套接字验证完整查询跨地址共享截止时间，不依赖真实网络延迟。 */
class HiveMetastoreBudgetTest {
  /** 保留真实查询/Thrift流程，只控制套接字耗时与单调时间。 */
  static class ControlledClient extends HiveMetastoreClient {
    long now, allocationDelay;
    int allocations;
    final Deque<Socket> sockets = new ArrayDeque<>();
    final List<Integer> connects = new ArrayList<>(), reads = new ArrayList<>();

    /** 使用实际JSON投影器。 */
    ControlledClient() { super(new ObjectMapper()); }
    /** 推进受控单调时间。 */
    void advance(long milliseconds) { now += TimeUnit.MILLISECONDS.toNanos(milliseconds); }
    /** 返回同一次查询的当前时间。 */
    @Override long nanoTime() { return now; }
    /** 在可控分配/解析阶段耗时后返回下一条连接。 */
    @Override Socket openSocket() { allocations++; advance(allocationDelay); return sockets.removeFirst(); }

    /** 建立按连接或读取超时失败的套接字，并记录调用时实际预算。 */
    Socket connection(long elapsed, boolean failConnect) throws Exception {
      Socket socket = mock(Socket.class);
      int[] timeout = {0};
      doAnswer(call -> { timeout[0] = call.getArgument(0); return null; }).when(socket).setSoTimeout(anyInt());
      doAnswer(call -> {
        connects.add(call.getArgument(1)); advance(elapsed);
        if (failConnect) throw new SocketTimeoutException("controlled connect");
        return null;
      }).when(socket).connect(any(SocketAddress.class), anyInt());
      when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
      when(socket.getInputStream()).thenReturn(new InputStream() {
        /** 每次读取消耗实际设置的剩余超时。 */
        @Override public int read() throws IOException { reads.add(timeout[0]); advance(timeout[0]); throw new SocketTimeoutException("controlled read"); }
        /** Thrift批量读取同样受受控超时限制。 */
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { return read(); }
      });
      sockets.add(socket); return socket;
    }
  }

  /** 前一次慢读取减少后续读取预算，8秒用尽时不创建第三条连接。 */
  @Test void slowReadsAndFallbackShareOneDeadline() throws Exception {
    var client = new ControlledClient();
    var first = client.connection(2000, false); var second = client.connection(2000, false);
    assertThatThrownBy(() -> client.metadata(List.of(URI.create("thrift://127.0.0.1:1"), URI.create("thrift://127.0.0.1:2"), URI.create("thrift://127.0.0.1:3")), "", "", false)).isInstanceOf(BusinessException.class);
    assertThat(client.connects).containsExactly(2000, 2000);
    assertThat(client.reads).containsExactly(3000, 1000);
    assertThat(client.allocations).isEqualTo(2);
    assertThat(client.now).isEqualTo(TimeUnit.SECONDS.toNanos(8));
    verify(first).close(); verify(second).close();
  }

  /** 即使调度或解析阶段占用了预算，回退连接也不能重新获得完整两秒。 */
  @Test void fallbackConnectUsesOnlyRemainingMilliseconds() throws Exception {
    var client = new ControlledClient();
    client.connection(6500, true); client.connection(1500, true);
    assertThatThrownBy(() -> client.metadata(List.of(URI.create("thrift://127.0.0.1:1"), URI.create("thrift://127.0.0.1:2"), URI.create("thrift://127.0.0.1:3")), "", "", false)).isInstanceOf(BusinessException.class);
    assertThat(client.connects).containsExactly(2000, 1500);
    assertThat(client.allocations).isEqualTo(2);
  }

  /** 分配/解析返回时预算已耗尽，不执行零超时或新连接，套接字仍被关闭。 */
  @Test void exhaustedBeforeConnectDoesNotStartBlockingOperation() throws Exception {
    var client = new ControlledClient();
    var socket = client.connection(0, true); client.allocationDelay = 8000;
    assertThatThrownBy(() -> client.metadata(List.of(URI.create("thrift://127.0.0.1:1"), URI.create("thrift://127.0.0.1:2")), "", "", false)).isInstanceOf(BusinessException.class);
    verify(socket, never()).connect(any(SocketAddress.class), anyInt());
    verify(socket).close(); assertThat(client.allocations).isEqualTo(1);
  }
}
