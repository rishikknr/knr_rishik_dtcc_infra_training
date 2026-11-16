package com.trace.backend.exception;

import java.util.List;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  public record ErrorResponse(String code, String message, List<ApiException.Detail> errors) {}

  private ResponseEntity<ErrorResponse> error(
      HttpStatusCode s, String c, String m, List<ApiException.Detail> d) {
    return ResponseEntity.status(s).body(new ErrorResponse(c, m, d));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> api(ApiException e) {
    return error(e.status, e.code, e.getMessage(), e.details);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
    String code =
        switch (e.getStatusCode().value()) {
          case 401 -> "AUTHENTICATION_REQUIRED";
          case 403 ->
              "Active organization membership required".equals(e.getReason())
                  ? "ORGANIZATION_ACCESS_REVOKED"
                  : "Select an active organization".equals(e.getReason())
                      ? "ORGANIZATION_REQUIRED"
                      : "ACCESS_DENIED";
          case 404 -> "NOT_FOUND";
          default -> "INVALID_REQUEST";
        };
    return error(
        e.getStatusCode(),
        code,
        e.getReason() == null ? "Request failed" : e.getReason(),
        List.of());
  }

  @ExceptionHandler(org.springframework.dao.PermissionDeniedDataAccessException.class)
  public ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Permission denied", List.of());
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ErrorResponse> databaseFailure(DataAccessException e) {
    Throwable cause = e.getMostSpecificCause();
    if (cause instanceof java.sql.SQLException sql) {
      if ("P0002".equals(sql.getSQLState()))
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", List.of());
      if ("42501".equals(sql.getSQLState())) return forbidden();
      if ("57014".equals(sql.getSQLState()))
        return error(
            HttpStatus.SERVICE_UNAVAILABLE,
            "QUERY_TIMEOUT",
            "This listing exceeded its query budget. Narrow the filter and retry.",
            List.of());
    }
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Request could not be completed",
        List.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> conflict() {
    return error(
        HttpStatus.CONFLICT,
        "RESOURCE_CONFLICT",
        "Resource conflict or invalid reference",
        List.of());
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    ServletRequestBindingException.class,
    HttpMessageNotReadableException.class
  })
  public ResponseEntity<ErrorResponse> invalid() {
    return error(
        HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Missing or invalid request fields", List.of());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> large() {
    return error(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "FILE_TOO_LARGE",
        "CSV file must not exceed 5 MiB",
        List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> internal(Exception e) {
    // Keep Spring MVC's 404/405/415 statuses while returning the same safe contract.
    if (e instanceof org.springframework.web.ErrorResponse webError) {
      return error(
          webError.getStatusCode(), "INVALID_REQUEST", "Request could not be accepted", List.of());
    }
    org.slf4j.LoggerFactory.getLogger(getClass())
        .error("Request failed ({})", e.getClass().getSimpleName());
    return error(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "Request could not be completed",
        List.of());
  }
}
