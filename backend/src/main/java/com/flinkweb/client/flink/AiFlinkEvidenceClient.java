package com.flinkweb.client.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.config.platform.PlatformProperties;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** 只读诊断固定Flink REST端点，目标来自已核验作业名，禁止任意URL和重定向。 */
@Component
public class AiFlinkEvidenceClient {
  private final String namespace;
  private final ObjectMapper mapper;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

  /** 使用平台固定namespace，不读取Flink配置中的可控REST地址。 */
  public AiFlinkEvidenceClient(PlatformProperties properties,ObjectMapper mapper) {this.namespace=properties.namespace();this.mapper=mapper;}

  /** GET端点按白名单固定，普通JSON最多64KiB、Checkpoint与TM列表最多2MiB；日志尾部4MiB、传输64MiB。 */
  public JsonNode read(String deployment,String path) {
    if(!deployment.matches("[a-z0-9][a-z0-9.-]{0,252}") || !path.matches("(overview|jobmanager/(log|metrics(\\?get=[A-Za-z0-9.,_-]+)?)|taskmanagers|taskmanagers/[A-Za-z0-9_.:-]{1,200}/(?:log|metrics(\\?get=[A-Za-z0-9.,_-]+)?)|jobs/[a-f0-9]{32}/(exceptions\\?maxExceptions=20|checkpoints(?:/config|/details/[0-9]+(?:/subtasks/[a-f0-9]{32})?)?))"))throw new IllegalArgumentException("不允许的Flink取证端点");
    var request=HttpRequest.newBuilder(URI.create("http://"+deployment+"-rest."+namespace+".svc:8081/"+path)).timeout(Duration.ofSeconds(3)).GET().build();
    try {
      var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
      try(var input=response.body()) {
        if(response.statusCode()!=200)throw new IllegalStateException("Flink REST HTTP "+response.statusCode());
        if(path.endsWith("/log")) {
          byte[] ring=new byte[4*1024*1024],chunk=new byte[8192];int position=0,total=0,count;
          while((count=input.read(chunk))!=-1) {
            total+=count;if(total>64*1024*1024)throw new IllegalStateException("Flink日志超过本轮64MiB传输上限");
            for(int i=0;i<count;i++){ring[position]=chunk[i];position=(position+1)%ring.length;}
          }
          int size=Math.min(total,ring.length),start=total<ring.length?0:position;
          byte[] tail=new byte[size];for(int i=0;i<size;i++)tail[i]=ring[(start+i)%ring.length];
          return mapper.createObjectNode().put("text",new String(tail,StandardCharsets.UTF_8)).put("truncated",total>ring.length);
        }
        int limit=path.contains("/checkpoints")||path.equals("taskmanagers")?2*1024*1024:65536;byte[] bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new IllegalStateException("Flink证据超过响应预算");return mapper.readTree(bytes);
      }
    } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("Flink取证已取消",error);}
      catch(java.io.IOException error) {throw new IllegalStateException("Flink REST暂不可达",error);}
  }
}
