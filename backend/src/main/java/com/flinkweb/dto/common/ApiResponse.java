package com.flinkweb.dto.common;

/** 统一 API 成功及失败的响应结构。 */
public record ApiResponse<T>(T data, ApiError error) {
  /** 封装成功结果。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, null);
  }

  /** 封装稳定错误码和用户可读的失败说明。 */
  public static ApiResponse<Void> fail(String code, String message) {
    return new ApiResponse<>(null, new ApiError(code, message));
  }
}
