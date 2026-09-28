package com.flinkweb.service.job.creation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/** 服务契约：将表单输入与默认模板、镜像版本、PodTemplate 和制品配置合成为 YAML。 */
public interface FormService {
  /** 读取模板和镜像版本，填入表单参数、PodTemplate 及制品下载配置，返回 YAML。 */
  ObjectNode render(ObjectNode input);
}
