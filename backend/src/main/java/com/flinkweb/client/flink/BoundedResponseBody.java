package com.flinkweb.client.flink;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** 有界响应订阅器；完整正文在预算内完成，取消同时关闭上游，不留下阻塞读取线程。 */
final class BoundedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
  private byte[] tail = new byte[0];
  private final int capacity;
  private final long maximum;
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Subscription subscription;
  private volatile boolean canceled;
  private int position, size;
  private long total;

  /** 只保存尾部容量上限，收到正文后再增长分配，空响应不预分配。 */
  BoundedResponseBody(int capacity, long maximum) {
    if (capacity <= 0 || maximum <= 0)
      throw new IllegalArgumentException("Invalid response limits");
    this.capacity = capacity;
    this.maximum = maximum;
  }

  /** 完整正文完成后才结束请求 Future。 */
  public CompletionStage<byte[]> getBody() {
    return body;
  }

  /** 保存可取消订阅，并请求下一批数据。 */
  public void onSubscribe(Subscription value) {
    subscription = value;
    if (canceled) value.cancel();
    else value.request(1);
  }

  /** 分块累计和滚动尾部缓存，不持有整个文件。 */
  public void onNext(List<ByteBuffer> buffers) {
    try {
      if (canceled) return;
      for (var buffer : buffers) {
        total += buffer.remaining();
        if (total > maximum) throw new java.io.IOException("Flink response exceeds transfer limit");
        grow((int) Math.min(capacity, total));
        while (buffer.hasRemaining()) {
          int length = Math.min(buffer.remaining(), tail.length - position);
          buffer.get(tail, position, length);
          position = (position + length) % tail.length;
          size = Math.min(tail.length, size + length);
        }
      }
      subscription.request(1);
    } catch (Exception error) {
      cancel();
      body.completeExceptionally(error);
    }
  }

  /** 倍增至实际所需容量，达到尾部上限后按环形缓冲覆盖，增长时保留原顺序。 */
  private void grow(int required) {
    if (required <= tail.length) return;
    int length = (int) Math.min(capacity, Math.max(required, Math.max(8192L, tail.length * 2L)));
    tail = java.util.Arrays.copyOf(tail, length);
    position = size;
  }

  /** 将通信异常传递给等待者。 */
  public void onError(Throwable error) {
    body.completeExceptionally(error);
  }

  /** 返回按原顺序排列的末尾字节。 */
  public void onComplete() {
    byte[] result = new byte[size];
    int first = size == tail.length ? position : 0, length = Math.min(size, tail.length - first);
    System.arraycopy(tail, first, result, 0, length);
    System.arraycopy(tail, 0, result, length, size - length);
    body.complete(result);
  }

  /** 超时和中断主动释放订阅，不等待下一批正文。 */
  void cancel() {
    canceled = true;
    if (subscription != null) subscription.cancel();
    body.completeExceptionally(new CancellationException("Response subscription canceled"));
  }
}
