package com.egobb.plan.service.contract.controller;

import com.egobb.plan.service.contract.controller.dto.ApiResponse;
import com.egobb.plan.service.contract.resilience.SearchTooBusyException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

	public static final String INVALID_REQUEST = "INVALID_REQUEST";
	public static final String TOO_BUSY = "TOO_BUSY";
	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		// Example: starts_at=foo (cannot parse OffsetDateTime)
		final String param = ex.getName();
		final String message = "Invalid value for parameter '" + param + "'";
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(INVALID_REQUEST, message));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
		final String message = "Missing required parameter '" + ex.getParameterName() + "'";
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(INVALID_REQUEST, message));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException ex) {
		// Example: limit=0 (violates @Min(1))
		final String message = ex.getConstraintViolations().stream().map(GlobalExceptionHandler::formatViolation)
				.collect(Collectors.joining("; "));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(INVALID_REQUEST, message));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
		// Example: starts_at invalid ISO-8601 (we parse manually to support
		// offset-aware and local date-time inputs)
		final String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(INVALID_REQUEST, message));
	}

	@ExceptionHandler(SearchTooBusyException.class)
	public ResponseEntity<ApiResponse<Object>> handleTooBusy(SearchTooBusyException ex) {
		final String message = ex.getMessage() == null ? "Service is temporarily overloaded" : ex.getMessage();
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(TOO_BUSY, message));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Object>> handleGeneric(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.error(INTERNAL_ERROR, "Unexpected error"));
	}

	private static String formatViolation(ConstraintViolation<?> v) {
		// propertyPath typically looks like: search.limit / search.offset
		final String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
		final String cleanPath = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
		return cleanPath + ": " + v.getMessage();
	}
}
