package com.sphinxfin.sphinx.evidence;

import com.sphinxfin.sphinx.core.CreateSessionCommand;
import com.sphinxfin.sphinx.core.EvidenceRecorder;
import com.sphinxfin.sphinx.core.Session;
import com.sphinxfin.sphinx.core.SessionService;
import com.sphinxfin.sphinx.domain.Channel;
import com.sphinxfin.sphinx.domain.Grade;
import com.sphinxfin.sphinx.domain.Judgment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * evidence 배선 확인. 소유: 정세현
 *
 * <p>여기서 보는 것은 <b>구현이 실제로 등록됐는가</b> 하나다. {@code SessionService}는
 * {@code Optional<EvidenceRecorder>}를 받아 없으면 {@link EvidenceRecorder#NO_OP}으로 대체하는데,
 * <b>NO_OP은 아무 일도 안 하면서 성공한다.</b> 등록이 빠져도 모든 테스트가 초록이고 요청도
 * 200이며, 리포트를 뽑는 시점에야 "기록이 하나도 없다"가 드러난다.
 *
 * <p>그래서 단위 테스트로는 부족하다 — 전체 컨텍스트를 띄워 <b>실제로 주입되는 구현</b>을 보고,
 * 실제 흐름을 한 번 태워 H2에 남는지까지 확인한다.
 */
@SpringBootTest
@DisplayName("evidence 배선 — NO_OP이 아니라 실제 구현이 등록됐는가")
class EvidenceWiringTest {

    @Autowired
    private EvidenceRecorder recorder;
    @Autowired
    private SessionService sessions;
    @Autowired
    private ImmutableStore store;

    @Test
    @DisplayName("주입되는 구현이 NO_OP이 아니다")
    void realImplementationIsRegistered() {
        assertThat(recorder)
                .as("NO_OP이면 적재가 조용히 사라지고, 그 사실은 리포트를 뽑을 때야 드러난다")
                .isNotSameAs(EvidenceRecorder.NO_OP)
                .isInstanceOf(StoredEvidenceRecorder.class);
    }

    @Test
    @DisplayName("판정을 기록하면 세션 스트림에 남고 사슬이 검증된다 (ADR-004 — 같은 트랜잭션)")
    void recordJudgmentLeavesVerifiableEvidence() {
        Session session = sessions.create(new CreateSessionCommand(
                "ELS-001", Channel.FACE_TO_FACE, "60대", "없음", "5천만원대",
                "CT-1", "SUIT-v1", Map.of()));

        sessions.recordJudgment(session.id(), new Judgment(
                "ELS-PRINCIPAL-LOSS-WARNING", Grade.U3, new BigDecimal("0.7"),
                new Judgment.Evidence("원금은 지켜지죠", "원금손실 조건"),
                "부분 이해", null));
        sessions.recordJudgment(session.id(), new Judgment(
                "ELS-PRINCIPAL-LOSS-WARNING", Grade.U1, new BigDecimal("0.95"),
                new Judgment.Evidence("원금이 깨질 수 있다고 들었어요", "원금손실 조건"),
                "이해함", null));

        String stream = StoredEvidenceRecorder.streamOf(session.id());

        assertThat(store.replay(stream))
                .as("세션은 최신 판정만 들고 있다 — 'U3였다가 U1이 됐다'는 여기에만 남는다")
                .hasSize(2);
        assertThat(store.verify(stream).ok())
                .as("적재가 세션 저장과 같은 트랜잭션에서 끝났고 사슬이 온전하다")
                .isTrue();
        assertThat(sessions.get(session.id()).judgmentFor("ELS-PRINCIPAL-LOSS-WARNING").grade())
                .as("세션 쪽은 최신값만 남는다")
                .isEqualTo(Grade.U1);
    }
}
