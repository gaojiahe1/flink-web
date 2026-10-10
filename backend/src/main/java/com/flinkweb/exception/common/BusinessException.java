package com.flinkweb.exception.common;

import org.springframework.http.HttpStatus;

/** 承载业务拒绝原因、HTTP 状态及稳定错误码，交由统一异常处理器输出。 */
public class BusinessException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  /** 保存 HTTP 状态、业务错误码及用户可读说明。 */
  public BusinessException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  /** 保存原始异常链，供后端日志定位根因；客户端仍只收到业务说明。 */
  public BusinessException(HttpStatus status, String code, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.code = code;
  }

  /** 返回业务错误对应的 HTTP 状态。 */
  public HttpStatus status() {
    return status;
  }

  /** 返回前端可识别的业务错误码。 */
  public String code() {
    return code;
  }
}
