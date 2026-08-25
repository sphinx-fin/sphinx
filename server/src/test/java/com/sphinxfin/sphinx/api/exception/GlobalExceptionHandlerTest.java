package com.sphinxfin.sphinx.api.exception;

import com.sphinxfin.sphinx.core.ReExplainNotEligibleException;
import com.sphinxfin.sphinx.core.ReverifyExhaustedException;
import com.sphinxfin.sphinx.domain.EvidenceRequiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예외 → HTTP 상태·에러 코드 매핑을 고정한다. 소유: 강희진
 *
 * 프론트가 error.code를 유니온 타입으로 분기하므로 이 매핑이 계약이다
 * (contracts/openapi.yaml ApiError.code enum과 같아야 한다).
 * 실제 세션 흐름으로는 재현하기 어려운 예외(P4 위반 등)를 스텁 컨트롤러로 태운다.
 */
@DisplayName("GlobalExceptionHandler 예외 → 코드 매핑")
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("P4 위반(근거 없는 판정) → 502 EVIDENCE_REQUIRED — 400이 아니다")
    void evidenceRequiredIs502() throws Exception {
        // ai-service가 evidence를 빼먹은 것은 상류 계약 위반이지 고객 입력 오류가 아니다.
        // 400으로 나가면 화면이 "입력을 다시 확인해 주세요"를 띄우는데 고객이 고칠 게 없다.
        mvc.perform(get("/boom/evidence"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_REQUIRED"))
                .andExpect(jsonPath("$.error.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("일반 IllegalArgumentException → 500 INTERNAL_ERROR — 400으로 새지 않는다")
    void plainIllegalArgumentIs500() throws Exception {
        // 포괄 핸들러가 있던 시절엔 게이트 룰 파싱 실패 같은 서버 설정 오류까지 400이 됐다.
        mvc.perform(get("/boom/illegal-argument"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("서버 내부 오류"));   // 원인 미노출
    }

    @Test
    @DisplayName("재설명 대상 아님 → 400 REEXPLAIN_NOT_ELIGIBLE")
    void notEligibleIs400() throws Exception {
        mvc.perform(get("/boom/not-eligible"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REEXPLAIN_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("재검증 상한 도달 → 400 REVERIFY_EXHAUSTED")
    void exhaustedIs400() throws Exception {
        mvc.perform(get("/boom/exhausted"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REVERIFY_EXHAUSTED"));
    }

    /** 테스트 전용 — 실제 흐름으로 만들기 번거로운 예외를 던지기만 한다. */
    @RestController
    static class ThrowingController {
        @GetMapping("/boom/evidence")
        void evidence() {
            throw new EvidenceRequiredException("근거 없는 판정은 무효 (P4)");
        }

        @GetMapping("/boom/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("알 수 없는 게이트 룰 조건: anyGrade ~~ 'U9'");
        }

        @GetMapping("/boom/not-eligible")
        void notEligible() {
            throw new ReExplainNotEligibleException("재설명 대상이 아니다: A");
        }

        @GetMapping("/boom/exhausted")
        void exhausted() {
            throw new ReverifyExhaustedException("재검증 상한(2회) 도달: A");
        }
    }
}
