package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.service.ai.support.AiHarnessProgress;
import org.junit.jupiter.api.Test;

/** 重复失败、交替循环和有意义的长链分别验证，不依赖真实模型可靠性。 */
class AiHarnessProgressTest {
  private final ObjectMapper mapper=new ObjectMapper();

  /** 修改行列号或交替错误不能让弱模型无限重试。 */
  @Test
  void repeatedAndAlternatingFailuresStop(){
    var progress=new AiHarnessProgress(mapper);
    for(int n=0;n<3;n++){
      var output=mapper.createObjectNode().put("error","SQL_CHECK_FAILED").put("message","第"+(n+1)+"条语句，第11行，第5列：SQL语法错误");
      assertThat(progress.record("sql_propose",mapper.createObjectNode().put("sql","bad "+n),output).path("action").asText()).isEqualTo(n==0?"continue":n==1?"recover":"stop");
    }
    var alternating=new AiHarnessProgress(mapper);
    for(int n=0;n<5;n++){
      var output=mapper.createObjectNode().put("error",n%2==0?"A":"B").put("message","不可用");
      if(n==4)assertThat(alternating.record("sql_propose",mapper.createObjectNode(),output).path("action").asText()).isEqualTo("stop");else alternating.record("sql_propose",mapper.createObjectNode(),output);
    }
  }

  /** 证据时间戳和JSON键顺序不构成新进展；反复成功读取也会停止循环。 */
  @Test
  void timestampsAndKeyOrderDoNotHideReadLoop(){
    var progress=new AiHarnessProgress(mapper);
    for(int n=0;n<3;n++){
      var output=mapper.createObjectNode().put("observedAt","time-"+n);if(n%2==0)output.put("state","FAILED").put("reason","same");else output.put("reason","same").put("state","FAILED");
      var result=progress.record("job_status",mapper.createObjectNode(),output);
      if(n==2)assertThat(result.path("action").asText()).isEqualTo("stop");
    }
  }

  /** 不设总调用次数上限，持续产生新检查结果的长工具链不会被停止。 */
  @Test
  void meaningfulProgressHasNoTotalCallLimit(){
    var progress=new AiHarnessProgress(mapper);
    for(int n=0;n<150;n++){
      var output=mapper.createObjectNode();output.putObject("check").put("valid",true).put("message","候选检查通过");
      assertThat(progress.record("sql_check",mapper.createObjectNode().put("sql","SELECT "+n),output).path("action").asText()).isEqualTo("continue");
    }
  }

  /** 单纯改写记忆不能被认作解决问题，达到连续无进展阈值会停止。 */
  @Test
  void memoryChurnIsNotProgress(){
    var progress=new AiHarnessProgress(mapper);
    for(int n=0;n<12;n++){
      var result=progress.record("memory_update",mapper.createObjectNode().put("summary","文字变化"+n),mapper.createObjectNode().put("memoryCandidate","文字变化"+n));
      if(n==11)assertThat(result.path("action").asText()).isEqualTo("stop");
    }
  }
}
