package com.flinkweb.exception.platform;

/** 保存启动配置错误及修复步骤，禁止携带密码、主密钥或访问凭据。 */
public class StartupConfigurationException extends IllegalStateException {
  private final String action;

  /** 接收可安全输出的失败原因与部署修复说明。 */
  public StartupConfigurationException(String message, String action) {
    super(message);
    this.action = action;
  }

  /** 返回启动失败报告中显示的修复步骤。 */
  public String action() {
    return action;
  }
}
