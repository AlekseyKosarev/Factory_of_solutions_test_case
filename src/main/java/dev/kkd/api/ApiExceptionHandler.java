package dev.kkd.api;

import dev.kkd.application.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  public record Error(String code, String message) {}

  public record Envelope(Error error) {}

  @ExceptionHandler(ServiceException.class)
  ResponseEntity<Envelope> service(ServiceException e) {
    return response(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Envelope> missing(NoResourceFoundException e) {
    return response(404, "NOT_FOUND", "Endpoint was not found");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Envelope> method(HttpRequestMethodNotSupportedException e) {
    return response(405, "METHOD_NOT_ALLOWED", "HTTP method is not supported");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Envelope> unexpected(Exception e) {
    log.error("Unhandled request failure", e);
    return response(500, "INTERNAL_ERROR", "Unexpected server error");
  }

  private ResponseEntity<Envelope> response(int status, String code, String message) {
    return ResponseEntity.status(status).body(new Envelope(new Error(code, message)));
  }
}
