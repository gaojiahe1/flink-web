package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.permission.UserService;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** 页面业务路由同源注册为工具；权限账号接口和递归AI编排不进入目录。 */
@Component
public class AiPlatformApiCatalog {
  private final ObjectProvider<RequestMappingHandlerMapping> mappings;
  private final ObjectMapper mapper;
  private final UserService users;
  /** 延迟读取MVC映射，避免控制器和工具目录构造循环。 */
  public AiPlatformApiCatalog(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings,ObjectMapper mapper,UserService users){this.mappings=mappings;this.mapper=mapper;this.users=users;}
  /** 描述保留实际HTTP方法、路径及安全规则，写工具只创建审阅请求。 */
  public record Operation(String name,String method,String path,HandlerMethod handler,boolean direct) {}
  /** 每次从真实路由构造，新增页面接口自动登记，排除规则默认拒绝跨模块入口。 */
  public Map<String,Operation> operations(){
    var out=new TreeMap<String,Operation>();mappings.getObject().getHandlerMethods().forEach((mapping,handler)->{
      String type=handler.getBeanType().getPackageName();if(!type.startsWith("com.flinkweb.controller.")||type.endsWith(".permission")||type.endsWith(".ai")&&!handler.getBeanType().getSimpleName().equals("AiModelController"))return;
      for(String path:mapping.getPatternValues())if(path.startsWith("/api/"))for(var method:mapping.getMethodsCondition().getMethods()){
        String name="api_"+handler.getBeanType().getSimpleName().replace("Controller", "").toLowerCase(Locale.ROOT)+"_"+handler.getMethod().getName().toLowerCase(Locale.ROOT)+"_"+Integer.toUnsignedString((method+path).hashCode(),36);
        boolean direct=method.name().equals("GET")&&!path.contains("download");
        if(out.put(name,new Operation(name,method.name(),path,handler,direct))!=null)throw new IllegalStateException("业务工具名称冲突");
      }
    });return out;
  }
  /** 当前账号必须仍启用且角色未变化，不能利用LLM缓存授权。 */
  public void identity(){var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated())deny();org.springframework.security.core.userdetails.UserDetails user;try{user=users.loadUserByUsername(auth.getName());}catch(org.springframework.security.core.userdetails.UsernameNotFoundException ex){deny();return;}if(!user.isEnabled()||!new HashSet<>(user.getAuthorities()).equals(new HashSet<>(auth.getAuthorities())))deny();}
  /** 权限发现和执行重复核验控制器角色；未知表达式拒绝而非放行。 */
  public boolean allowed(Operation op){
    identity();return roleAllowed(op);}
  /** 目录遍历仅建立一次账号快照，单次调用仍重新读取。 */
  private boolean roleAllowed(Operation op){var method=AnnotatedElementUtils.findMergedAnnotation(op.handler().getMethod(),PreAuthorize.class);var type=AnnotatedElementUtils.findMergedAnnotation(op.handler().getBeanType(),PreAuthorize.class);var rule=method!=null?method:type;if(rule==null)return true;
    String expr=rule.value();if(!expr.matches("has(?:Any)?Role\\('[A-Z_]+'(?:,\\s*'[A-Z_]+')*\\)"))return false;
    var matcher=Pattern.compile("'([A-Z_]+)'").matcher(expr);var auth=SecurityContextHolder.getContext().getAuthentication();while(matcher.find()){String role="ROLE_"+matcher.group(1);if(auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals(role)))return true;}return false;
  }
  /** 路由参数按原名提供，JSON字段契约由原MVC/Bean Validation再次验证。 */
  public ArrayNode tools(){identity();var rows=mapper.createArrayNode();for(var op:operations().values())if(roleAllowed(op)){
    var row=rows.addObject().put("name",op.name()).put("description",op.method()+" "+op.path()+"；"+(op.direct()?"读取业务页面数据":"提出具体请求供用户审阅，确认前不执行")+"；原接口权限/校验继续生效。");var schema=row.putObject("inputSchema").put("type","object").put("additionalProperties",false);var props=schema.putObject("properties");for(String key:List.of("path","query","body"))props.putObject(key).put("type","object").put("additionalProperties",true);props.putObject("offset").put("type","integer").put("minimum",0).put("description","列表结果分页字符外的项目偏移，每页最多50项");row.putObject("annotations").put("readOnlyHint",true).put("destructiveHint",false).put("openWorldHint",false);
    var contract=row.putObject("_meta");contract.put("method",op.method()).put("path",op.path()).put("requiresConfirmation",!op.direct());var params=contract.putArray("parameters");for(var param:op.handler().getMethodParameters()){var item=params.addObject().put("name",param.getParameterName()).put("javaType",param.getGenericParameterType().getTypeName());if(param.getParameterType().isRecord()){var fields=item.putArray("fields");for(var field:param.getParameterType().getRecordComponents())fields.addObject().put("name",field.getName()).put("type",field.getGenericType().getTypeName());}for(var ann:param.getParameterAnnotations())item.put(ann.annotationType().getSimpleName(),ann.toString());}
  }return rows;}
  /** 不存在的名称、权限降级或未启用账号不能执行。 */
  public Operation require(String name){var op=operations().get(name);if(op==null||!allowed(op))deny();return op;}
  /** 统一权限失败不回显凭据或接口内部状态。 */
  private static void deny(){throw new BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"MCP_API_FORBIDDEN","当前账号或角色不能调用该业务接口");}
}
