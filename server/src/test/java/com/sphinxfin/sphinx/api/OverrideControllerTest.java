package com.sphinxfin.sphinx.api;

import com.sphinxfin.sphinx.core.CreateSessionCommand;
import com.sphinxfin.sphinx.core.Session;
import com.sphinxfin.sphinx.core.SessionRepository;
import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.GateResult;
import com.sphinxfin.sphinx.domain.Signal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-GTE-002 오버라이드 API 통합. 응답 봉투·409·400 매핑을 실제 요청으로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-002 OverrideController (통합)")
class OverrideControllerTest {

    private static final String REASON =
            "고객이 이미 유사 상품 3년 경험이 있고 손실 위험을 서면으로 재확인하여 진행합니다.";  // 30자 이상

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SessionRepository repository;

    @Test
    @DisplayName("적색 세션 요청 → 200 + status=PENDING_APPROVAL, 이어 승인 → APPROVED")
    void requestThenApprove() throws Exception {
        String id = seed(Signal.RED);

        mvc.perform(post("/sessions/{sid}/override", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        mvc.perform(post("/sessions/{sid}/override/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 승인 뒤의 노출까지 본다. 이 둘이 S-07 리포트("오버라이드로 진행됨")가 쓸 값인데,
        // PENDING_APPROVAL 까지만 걸어두면 승인 경로에서 빠져도 안 드러난다 (#116 리뷰).
        mvc.perform(get("/sessions/{sid}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrideStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.overrideReason").value(REASON))
                .andExpect(jsonPath("$.data.overrideApprover").isNotEmpty())
                .andExpect(jsonPath("$.data.overrideDecidedAt").isNotEmpty());
    }

    @Test
    @DisplayName("사유 30자 미만 → 400 VALIDATION_ERROR")
    void shortReasonRejected() throws Exception {
        String id = seed(Signal.RED);

        mvc.perform(post("/sessions/{sid}/override", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"너무짧은사유\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("녹색 세션 요청 → 409 OVERRIDE_NOT_ELIGIBLE")
    void greenRejected() throws Exception {
        String id = seed(Signal.GREEN);

        mvc.perform(post("/sessions/{sid}/override", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OVERRIDE_NOT_ELIGIBLE"));
    }

    @Test
    @DisplayName("사유 null(본문 {}) → 400 VALIDATION_ERROR (@NotBlank로 우회 차단)")
    void nullReasonRejected() throws Exception {
        String id = seed(Signal.RED);

        // @Size만 있으면 null이 통과해 사유 없는 승인이 남는다(오준서 #68 리뷰) — @NotBlank가 막는다.
        mvc.perform(post("/sessions/{sid}/override", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("요청 후 GET /sessions/{id}가 오버라이드 사유·상태를 노출 (S-06 승인 화면 입력)")
    void sessionResponseExposesOverride() throws Exception {
        String id = seed(Signal.RED);
        mvc.perform(post("/sessions/{sid}/override", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + REASON + "\"}"));

        mvc.perform(get("/sessions/{sid}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrideStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.overrideReason").value(REASON));
    }

    /** 지정 신호로 판정 기록된 세션을 저장하고 id를 반환한다. */
    private String seed(Signal signal) {
        Session s = Session.create(new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE, "60대",
                "없음", "5천만원대", "CT-1", "SUIT-v1", Map.of()));
        s.recordGate(new GateResult(signal, List.of("R-01")), Instant.now());
        return repository.save(s).id();
    }
}
