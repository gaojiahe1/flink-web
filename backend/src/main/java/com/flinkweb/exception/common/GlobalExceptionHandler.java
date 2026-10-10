package com.flinkweb.exception.common;

import com.flinkweb.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一转换业务、校验、权限和数据库异常，避免向客户端泄露内部细节。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 将业务异常转换为其指定的 HTTP 状态和错误响应。 */
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiResponse<Void>> business(BusinessException ex, HttpServletRequest request) {
    if (ex.status().is5xxServerError() || ex.status() == HttpStatus.FORBIDDEN)
      log.warn(
          "API failed method={} resource={} actor={} status={} code={}",
          request.getMethod(),
          safe(request.getRequestURI()),
          actor(request),
          ex.status().value(),
          ex.code(),
          ex);
    return ResponseEntity.status(ex.status()).body(ApiResponse.fail(ex.code(), ex.getMessage()));
  }

  /** 汇总参数校验错误并返回错误请求响应。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
    var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
    return ResponseEntity.badRequest()
        .body(
            ApiResponse.fail(
                "VALIDATION_FAILED",
                error.map(e -> e.getField() + ": " + e.getDefaultMessage()).orElse("参数不正确")));
  }

  /** 处理请求体格式或参数绑定异常，避免暴露解析细节。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiResponse<Void>> invalidBody() {
    return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_BODY", "请求内容格式不正确"));
  }

  /** 将唯一键冲突转换为可理解的冲突响应。 */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiResponse<Void>> conflict() {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.fail("DATA_CONFLICT", "名称已存在或数据约束冲突"));
  }

  /** 将越权访问转换为禁止访问响应。 */
  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException ex, HttpServletRequest request) {
    log.warn(
        "API access denied method={} resource={} actor={} errorType={}",
        request.getMethod(),
        safe(request.getRequestURI()),
        actor(request),
        ex.getClass().getSimpleName(),
        ex);
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.fail("FORBIDDEN", "没有执行此操作的权限"));
  }

  /** 屏蔽数据库异常细节并返回持久化失败响应。 */
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiResponse<Void>> storage(DataAccessException ex) {
    log.error("Storage operation failed errorType={}", ex.getClass().getSimpleName());
    // 不记录 SQL 参数或用户提交的 YAML，避免敏感配置进入日志。
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql) {
        int code = sql.getErrorCode();
        if (sql instanceof org.sqlite.SQLiteException && (code & 255) == 19) return conflict();
        log.error(
            "Storage operation failed: type={}, SQLState={}, vendorCode={}",
            ex.getClass().getSimpleName(),
            sql.getSQLState(),
            code);
        if (sql instanceof org.sqlite.SQLiteException && ((code & 255) == 5 || (code & 255) == 6))
          return ResponseEntity.status(HttpStatus.CONFLICT)
              .body(ApiResponse.fail("STORAGE_BUSY", "元数据正在写入，请稍后重试"));
        break;
      }
    }
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail("STORAGE_ERROR", "存储操作失败，请检查服务端存储配置"));
  }

  /** 记录请求身份，不读取会话令牌或请求参数。 */
  private String actor(HttpServletRequest request) {
    return request.getUserPrincipal() == null
        ? "anonymous"
        : safe(request.getUserPrincipal().getName());
  }

  /** 限制日志字段长度并清除换行，避免日志注入。 */
  private String safe(String value) {
    String text = value.replaceAll("[\\r\\n\\t]", "_");
    return text.substring(0, Math.min(text.length(), 512));
  }
}
