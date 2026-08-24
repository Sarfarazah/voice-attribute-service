package com.example.voice.exception;

import com.example.voice.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest r) {
        return response(e.status(), e.code(), e.getMessage(), r);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> bad(Exception e, HttpServletRequest r) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A required request value is missing or invalid", r);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> large(Exception e, HttpServletRequest r) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Audio upload exceeds the configured size limit", r);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMedia(HttpMediaTypeNotSupportedException e, HttpServletRequest r) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Audio media type is not supported", r);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest r) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be processed", r);
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus s, String code, String message, HttpServletRequest r) {
        return ResponseEntity.status(s).body(new ErrorResponse(Instant.now(), s.value(), code, message, r.getRequestURI()));
    }
}
