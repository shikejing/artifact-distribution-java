package com.github.shikejing.artifactdistributionjava.web;

import com.github.shikejing.artifactdistributionjava.exception.AuthorizationException;
import com.github.shikejing.artifactdistributionjava.exception.ConflictException;
import com.github.shikejing.artifactdistributionjava.exception.NotFoundException;
import com.github.shikejing.artifactdistributionjava.exception.PackageVerificationException;
import com.github.shikejing.artifactdistributionjava.exception.QuotaExceededException;
import com.github.shikejing.artifactdistributionjava.exception.RolloutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常映射：把业务异常转为合适的 HTTP 状态码，
 * 响应体始终包含 {@code error} 与可核对的 {@code reason}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, String>> handleAuth(AuthorizationException e) {
        return build(HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException e) {
        return build(HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED", "缺少认证头: " + e.getHeaderName());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException e) {
        return build(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<Map<String, String>> handleQuota(QuotaExceededException e) {
        // 拒绝原因体现在消息中：配额/占用/需求量/超出量逐项可核对。
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTA_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler({PackageVerificationException.class, RolloutException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleUnprocessable(RuntimeException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
    }

    private ResponseEntity<Map<String, String>> build(HttpStatus status, String error, String reason) {
        return ResponseEntity.status(status).body(Map.of("error", error, "reason", reason));
    }
}
