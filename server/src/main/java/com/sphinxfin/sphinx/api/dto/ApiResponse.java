package com.sphinxfin.sphinx.api.dto;

/**
 * 공통 응답 봉투. 소유: 강희진
 *
 * 모든 성공 응답은 ApiResponse.ok(data), 모든 실패 응답은 GlobalExceptionHandler를 거쳐
 * ApiResponse.fail(error) 형태로 나간다 — 프론트가 success 하나로 분기할 수 있게.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
