package com.flinkweb.config.platform;

import com.flinkweb.exception.platform.StartupConfigurationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** 将被依赖注入异常包装的启动配置问题转换为清晰的原因和处理步骤。 */
public class StartupFailureAnalyzer extends AbstractFailureAnalyzer<StartupConfigurationException> {
  /** 输出不包含凭据的配置诊断，由 Spring Boot 标准启动失败报告呈现。 */
  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, StartupConfigurationException cause) {
    return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
  }
}
