package com.sphinxfin.sphinx.api.exception;

/**
 * 요청 값이 계약을 벗어났다 → 400 {@code VALIDATION_ERROR}. 소유: 강희진
 *
 * <p>{@code @Valid} 가 못 잡는 자리를 위한 것이다 — 쿼리 파라미터의 enum 값처럼 바인딩이
 * 아니라 <b>해석</b>에서 걸리는 것들.
 *
 * <p>❗{@code IllegalArgumentException} 을 통째로 400 에 매핑하지 않는다. 그러면 서버 설정
 * 오류(게이트 룰 파싱 실패 등)까지 "잘못된 요청" 이 되어, <b>500 이어야 할 것이 200 대 흐름
 * 처럼 보인다</b>(CLAUDE.md {@code api/} 절). 그래서 전용 타입을 만든다.
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
