package com.egobb.plan.service.contract.controller.dto;

public record ApiResponse<T>(T data, ApiError error) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(data, null);
	}

	public static <T> ApiResponse<T> error(String code, String message) {
		return new ApiResponse<>(null, new ApiError(code, message));
	}

	public static <T> ApiResponse<T> error(ApiError error) {
		return new ApiResponse<>(null, error);
	}
}
