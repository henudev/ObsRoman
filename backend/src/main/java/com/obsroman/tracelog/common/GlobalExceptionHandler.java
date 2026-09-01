package com.obsroman.tracelog.common;

import com.obsroman.tracelog.model.LogValidationException;
import com.obsroman.tracelog.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e) {
        return toResponse(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(LogValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleLogValidation(LogValidationException e) {
        return toResponse(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorageException(StorageException e) {
        log.error("storage error: {}", e.getMessage());
        ErrorCode code = e.getErrorCode() == ErrorCode.STORAGE_TIMEOUT
                ? ErrorCode.STORAGE_TIMEOUT
                : ErrorCode.STORAGE_ERROR;
        return toResponse(code, e.getErrorCode().defaultMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException
                ? "malformed request body"
                : e.getMessage();
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return toResponse(ErrorCode.NOT_FOUND, "resource not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return toResponse(ErrorCode.INTERNAL_ERROR, "internal server error");
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code, String message) {
        String msg = message == null || message.isBlank() ? code.defaultMessage() : message;
        if (code.httpStatus() >= 500) {
            log.warn("api error code={} message={}", code.code(), msg);
        }
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.error(code, msg));
    }
}
