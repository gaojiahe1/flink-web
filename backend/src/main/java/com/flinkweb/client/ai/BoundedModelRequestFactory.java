package com.flinkweb.client.ai;
import java.io.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.http.client.*;

/** Spring AI同步HTTP响应的字节预算，不实现模型协议或工具循环。 */
final class BoundedModelRequestFactory implements ClientHttpRequestFactory {
  private final ClientHttpRequestFactory delegate;
  private final int seconds;
  /** 包装Spring JDK传输，保留超时和禁止重定向。 */
  BoundedModelRequestFactory(ClientHttpRequestFactory delegate,int seconds){this.delegate=delegate;this.seconds=seconds;}
  /** 为每个请求建立独立响应计数器。 */
  public ClientHttpRequest createRequest(URI uri,HttpMethod method)throws IOException {
    var request=delegate.createRequest(uri,method);
    return new ClientHttpRequest(){
      /** 保持框架选择的请求方法。 */
      public HttpMethod getMethod(){return request.getMethod();}
      /** 保持已校验请求目标。 */
      public URI getURI(){return request.getURI();}
      /** 认证头只交给框架传输。 */
      public HttpHeaders getHeaders(){return request.getHeaders();}
      /** 透传框架请求属性。 */
      public java.util.Map<String,Object> getAttributes(){return request.getAttributes();}
      /** 请求体由Spring AI序列化。 */
      public OutputStream getBody()throws IOException{return request.getBody();}
      /** 限制完整响应，异常时关闭连接。 */
      public ClientHttpResponse execute()throws IOException {
        long started=System.nanoTime();var response=request.execute();long remaining=java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds)-(System.nanoTime()-started);if(remaining<=0){response.close();throw new java.net.SocketTimeoutException("模型请求超过截止时间");}
        var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"ai-response-deadline");thread.setDaemon(true);return thread;});timer.schedule(response::close,remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
        var bounded=new FilterInputStream(response.getBody()){
          private long count;
          /** 单字节读取也受总预算限制。 */
          public int read()throws IOException{int value=in.read();if(value>=0)check(1);return value;}
          /** 数组读取累积总字节，不重置计数。 */
          public int read(byte[] bytes,int offset,int length)throws IOException{int n=in.read(bytes,offset,length);if(n>0)check(n);return n;}
          /** 超过一MiB关闭上游并明确失败。 */
          private void check(int n)throws IOException{count+=n;if(count>1024*1024){response.close();throw new IOException("模型响应超过大小限制");}}
        };
        return new ClientHttpResponse(){
          /** 保留HTTP状态供稳定错误分类使用。 */
          public HttpStatusCode getStatusCode()throws IOException{return response.getStatusCode();}
          /** 保留状态文本但不记录它。 */
          public String getStatusText()throws IOException{return response.getStatusText();}
          /** 确保连接可释放。 */
          public void close(){timer.shutdownNow();response.close();}
          /** 只向框架暴露受限响应流。 */
          public InputStream getBody(){return bounded;}
          /** 保留协议内容类型。 */
          public HttpHeaders getHeaders(){return response.getHeaders();}
        };
      }
    };
  }
}
