package com.flinkweb.service.ai.support;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
/** Kubernetes只读白名单固定namespace；不暴露任意路径、Secret、RBAC、exec或代理。 */
@Component public class AiKubernetesTools {
  private final KubernetesClient client;private final AccessService access;private final ObjectMapper mapper;
  private static final Map<String,String> KINDS=Map.of("pods","/api/v1","services","/api/v1","events","/api/v1","resourcequotas","/api/v1","limitranges","/api/v1","deployments","/apis/apps/v1","replicasets","/apis/apps/v1","statefulsets","/apis/apps/v1","flinkdeployments","/apis/flink.apache.org/v1beta1");
  /** 注入已有K8s客户端，沿用TLS/凭据、固定namespace和超时。 */
  public AiKubernetesTools(KubernetesClient client,AccessService access,ObjectMapper mapper){this.client=client;this.access=access;this.mapper=mapper;}
  /** 每次调用检查管理员角色，投影资源而不外发容器环境、卷和注解正文。 */
  public ObjectNode read(String tool,ObjectNode args){if(!access.admin())throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","Kubernetes资源查询仅管理员可用");String kind=args.path("kind").asText(),prefix=KINDS.get(kind),name=args.path("name").asText();if(prefix==null||tool.equals("k8s_get")&&!name.matches("[a-z0-9][a-z0-9.-]{0,252}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"K8S_RESOURCE_INVALID","不支持的资源类型或名称");String namespace=client.core().substring("/api/v1/namespaces/".length());String path=prefix+"/namespaces/"+namespace+"/"+kind+(tool.equals("k8s_get")?"/"+name:"");var data=client.call("GET",path,null);var out=mapper.createObjectNode().put("namespace",namespace).put("kind",kind).put("queriedAt",java.time.Instant.now().toString());if(data.isMissingNode())return out.put("status","MISSING");var items=out.putArray("items");if(tool.equals("k8s_get"))items.add(project(data));else{int offset=args.path("offset").asInt();if(offset<0)throw new BusinessException(HttpStatus.BAD_REQUEST,"K8S_OFFSET_INVALID","偏移须非负");var all=data.path("items");for(int n=offset;n<Math.min(all.size(),offset+50);n++)items.add(project(all.get(n)));out.put("total",all.size());if(offset+50<all.size())out.put("nextOffset",offset+50);}return out;}
  /** 明确的输出字段白名单，未知扩展字段不能默认外发。 */
  private ObjectNode project(JsonNode data){var out=mapper.createObjectNode();var meta=out.putObject("metadata");for(String key:List.of("name","namespace","uid","resourceVersion","generation","creationTimestamp","ownerReferences"))if(data.path("metadata").has(key))meta.set(key,data.path("metadata").get(key));for(String key:List.of("type","reason","message","count","lastTimestamp","involvedObject"))if(data.has(key))out.set(key,data.get(key));if(data.has("status")){var status=out.putObject("status");for(String key:List.of("phase","reason","message","conditions","replicas","readyReplicas","availableReplicas","lifecycleState","error","jobStatus"))if(data.path("status").has(key))status.set(key,data.path("status").get(key));}var spec=out.putObject("spec");for(String key:List.of("replicas","selector","ports","type","clusterIP","image","flinkVersion","serviceAccount"))if(data.path("spec").has(key))spec.set(key,data.path("spec").get(key));return out;}
}
