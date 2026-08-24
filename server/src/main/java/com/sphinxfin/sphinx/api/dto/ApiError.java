package com.sphinxfin.sphinx.api.dto;

import java.time.Instant;

/** 공통 에러 상세. ApiResponse.error에 담긴다. 소유: 강희진 */
public record ApiError(String code, String message, Instant timestamp) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
