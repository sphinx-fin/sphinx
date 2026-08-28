package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import com.sphinxfin.sphinx.evidence.AuditLog;
import com.sphinxfin.sphinx.evidence.EvidenceEntryRepository;
import com.sphinxfin.sphinx.evidence.EvidenceStreamAnchorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-GTE-004 발행과 조회가 <b>감사에서 갈리는가</b>. 소유: 강희진
 *
 * <p>메서드를 POST/GET 으로 가르는 것만으로는 부족하다. {@code AuditInterceptor} 는
 * {@code @PreAuthorize} 문면에서 action 을 읽으므로(#76), 두 엔드포인트가 같은 action 이면
 * <b>로그에서 "읽었다"와 "교부했다"가 구별되지 않는다</b> — {@code resource}(URI)도 같고
 * HTTP 메서드는 담기지 않는다.
 *
 * <p>분쟁 시점에 답해야 하는 것은 <b>"언제 누가 교부했는가"</b> 이지 "누가 열어봤는가" 가
 * 아니다. 그래서 이 성질은 배선이 아니라 감사 기록으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("F-GTE-004 발행 ↔ 조회 감사 분리")
class ReportAuditSeparationTest {

    @Autowired private MockMvc mvc;
    @Autowired private AuditLog auditLog;
    @Autowired private EvidenceEntryRepository entries;
    @Autowired private EvidenceStreamAnchorRepository anchors;

    /** 감사 스트림은 트랜잭션 밖에서 커밋되므로 테스트 롤백으로 안 지워진다. */
    @BeforeEach
    void clearAuditStream() {
        entries.deleteAll();
        anchors.deleteAll();
    }

    private String createSession() throws Exception {
        String created = mvc.perform(post("/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.data.sessionId");
    }

    private List<String> auditedActionsFor(String resourceSuffix) {
        return auditLog.replay().stream()
                .map(e -> (Map<?, ?>) e.payload())
                .filter(p -> String.valueOf(p.get("resource")).endsWith(resourceSuffix))
                .map(p -> String.valueOf(p.get("action")))
                .toList();
    }

    // TODO(강희진): 역할별 계정 분리(결정 10.5, 8/29) 후 actorId 단정을 얹는다.
    //   이 변경의 주장은 "언제 **누가** 교부했는가" 인데 지금 테스트는 action 만 본다.
    //   지금 단정하면 단일 계정 값에 못박는 셈이라 계정이 갈린 뒤가 맞는 자리다 (#108 리뷰).

    @Test
    @DisplayName("❗발행과 조회가 서로 다른 action 으로 남는다 — 같으면 교부 시점을 못 답한다")
    void issueAndReadAreDistinctActions() throws Exception {
        String sid = createSession();

        mvc.perform(post("/sessions/" + sid + "/report")).andExpect(status().isOk());
        mvc.perform(get("/sessions/" + sid + "/report")).andExpect(status().isOk());

        List<String> actions = auditedActionsFor("/report");
        assertThat(actions)
                .as("URI 도 같고 HTTP 메서드는 감사에 안 담긴다 — action 이 유일한 구별 수단이다")
                .containsExactly("report:issue", "report:read");
    }

    @Test
    @DisplayName("조회만 해서는 발행 기록이 생기지 않는다 — 열람이 교부가 되면 안 된다")
    void readingDoesNotProduceIssueRecord() throws Exception {
        String sid = createSession();

        // **미발행 세션이라 404 다**(계약 GET 404). 목이던 시절에는 200 이었고, 배선이 붙으면서
        // 이 시나리오가 오히려 더 정확해졌다 — GET 이 "없으면 만들어 주는" 경로가 없다는 것을
        // 여기서 직접 본다. 두 번 읽어도 발행되지 않으므로 여전히 404 다.
        mvc.perform(get("/sessions/" + sid + "/report")).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/" + sid + "/report")).andExpect(status().isNotFound());

        assertThat(auditedActionsFor("/report"))
                .as("MGR·COMPL 이 남의 세션을 감독하려고 열어보는 것만으로 교부 기록이 생기면 "
                        + "교부 이력이 신뢰를 잃는다. 실패한 조회도 조회로 남는다 — "
                        + "AuditInterceptor 가 상태코드와 무관하게 기록한다(차단당한 시도가 신호다)")
                .containsOnly("report:read");
    }

    @Test
    @DisplayName("❗GET 은 발행하지 않는다 — 읽은 뒤에도 리포트는 여전히 없다")
    void readingDoesNotIssueTheReport() throws Exception {
        String sid = createSession();

        mvc.perform(get("/sessions/" + sid + "/report")).andExpect(status().isNotFound());
        mvc.perform(post("/sessions/" + sid + "/report")).andExpect(status().isOk());
        mvc.perform(get("/sessions/" + sid + "/report")).andExpect(status().isOk());

        // 감사 로그만 보면 "GET 이 발행했다" 와 "GET 은 안 했고 POST 가 했다" 가 구별되지만,
        // 그 구별은 action 이름에 기대고 있다. 상태 자체로도 확인한다 — 첫 GET 뒤에 리포트가
        // 없어야(404) GET 이 상태를 안 바꿨다는 것이 성립한다.
        assertThat(auditedActionsFor("/report"))
                .containsExactly("report:read", "report:issue", "report:read");
    }

    @Test
    @DisplayName("발행은 audited 다 — 목록에서 빠지면 교부가 감사에서 통째로 사라진다")
    void issueIsAudited() throws Exception {
        String sid = createSession();

        mvc.perform(post("/sessions/" + sid + "/report")).andExpect(status().isOk());

        assertThat(auditedActionsFor("/report"))
                .as("구별되지 않는 것보다 기록 자체가 없는 것이 나쁘다 — 0건이 "
                        + "'발행이 없었다' 로 읽힌다")
                .contains("report:issue");
    }
}
