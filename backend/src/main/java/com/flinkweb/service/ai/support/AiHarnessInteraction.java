package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 受限对话卡片契约，不接受模型提供执行地址或任意前端组件。 */
public final class AiHarnessInteraction {
  /** 校验模型请求并生成服务端交互身份及有效期。 */
  public static ObjectNode create(ObjectNode args) {
    args.fieldNames().forEachRemaining(k->{if(!Set.of("kind","title","questions","options").contains(k))invalid();});
    String kind=args.path("kind").asText();
    if(!Set.of("clarification","choice","logs").contains(kind)||!args.path("title").isTextual()||args.path("title").asText().isBlank()||args.path("title").asText().length()>500)invalid();
    for(String field:Set.of("questions","options")) {
      var list=args.path(field);if(!list.isArray()||list.size()>8)invalid();
      for(var item:list)if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>300)invalid();
    }
    if(kind.equals("clarification")&&args.path("questions").isEmpty()||kind.equals("choice")&&args.path("options").isEmpty())invalid();
    return args.deepCopy().put("id",UUID.randomUUID().toString()).put("state","PENDING").put("expiresAt",Instant.now().plusSeconds(86400).toString());
  }
  /** 用户答案只允许声明的字段和选项，补充文字仍视为用户输入。 */
  public static void validateAnswer(ObjectNode card,ObjectNode input) {
    for(String flag:Set.of("grantLogs","confirmedVerification"))if(input.has(flag)&&!input.path(flag).isBoolean())invalid();
    if(!input.path("decision").isTextual()||!Set.of("submit","cancel").contains(input.path("decision").asText()))invalid();
    if(input.path("decision").asText().equals("cancel"))return;
    var answers=input.path("answers");if(!answers.isObject()||answers.size()>9)invalid();
    answers.fields().forEachRemaining(f->{
      if(!f.getValue().isTextual()||f.getValue().asText().length()>2000)invalid();
      if(!f.getKey().equals("note")&&!f.getKey().equals("choice")&&!f.getKey().matches("q[0-7]"))invalid();
      if(f.getKey().matches("q[0-7]")&&Integer.parseInt(f.getKey().substring(1))>=card.path("questions").size())invalid();
    });
    if(card.path("kind").asText().equals("clarification")&&answers.path("note").asText().isBlank())for(int n=0;n<card.path("questions").size();n++)if(answers.path("q"+n).asText().isBlank())invalid();
    if(card.path("kind").asText().equals("choice")) {
      boolean found=false;for(var option:card.path("options"))if(option.asText().equals(answers.path("choice").asText()))found=true;
      if(!found&&answers.path("note").asText().isBlank())invalid();
    }
  }
  /** 无效输入不能构成确认或授权。 */
  private static void invalid(){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_INTERACTION","交互字段、选项或必填回答无效");}
}
