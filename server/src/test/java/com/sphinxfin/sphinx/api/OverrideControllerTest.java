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

    /** 지정 신호로 판정 기록된 세션을 저장하고 id를 반환한다. */
    private String seed(Signal signal) {
        Session s = Session.create(new CreateSessionCommand("ELS-001", Channel.FACE_TO_FACE, "60대",
                "없음", "5천만원대", "CT-1", "SUIT-v1", Map.of()));
        s.recordGate(new GateResult(signal, List.of("R-01")), Instant.now());
        return repository.save(s).id();
    }
}
