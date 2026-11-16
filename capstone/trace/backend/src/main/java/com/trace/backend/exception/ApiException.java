package com.trace.backend.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  public record Detail(Integer row, String field, String message) {}

  public final HttpStatus status;
  public final String code;
  public final List<Detail> details;

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ApiException(HttpStatus status, String code, String message, List<Detail> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}
