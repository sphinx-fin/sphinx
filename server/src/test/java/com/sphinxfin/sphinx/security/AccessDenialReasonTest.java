package com.sphinxfin.sphinx.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 거부 사유가 서버 로그까지 나오는가. 소유: 강희진
 *
 * <h2>왜 이 파일이 있나</h2>
 *
 * <p>{@link AccessPolicy.Decision} 은 <i>"막았다"</i> 와 <i>"판단할 수 없다"</i> 를 가르려고
 * 사유를 만든다. {@link AccessGuard} 가 {@code permits()}(불리언)만 부르던 동안 <b>그 사유는
 * 계산되고 버려졌다</b> — 아무 테스트도 안 깨졌다. 사유가 쓰이는 곳이 없었으니 당연하다.
 *
 * <p>드러나는 자리가 리허설이다. 403 응답은 전부 같은 문면이고 감사 로그는 {@code "403"} 만
 * 남기므로, <b>정책이 옳게 막은 것</b>과 <b>판단할 근거가 없어 막힌 것</b>이 구별되지 않는다.
 * 앞엣것은 시연 항목이고 뒤엣것은 결함이다(이슈 #166).
 *
 * <p>❗<b>응답이 사유를 싣지 않는 것까지 같이 잰다.</b> 사유를 노출하면 <i>"세션이 없다"</i> 와
 * <i>"자기 세션이 아니다"</i> 가 갈려서 남의 세션 ID 존재 여부를 응답으로 물어볼 수 있다.
 * 로그로 뺀 것을 응답으로 되돌리는 변경이 이 파일에서 걸린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("접근 거부 사유가 로그에 남는다 (이슈 #166)")
class AccessDenialReasonTest {

    private static final String NEW_SESSION = """
            {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}""";

    @Autowired private MockMvc mvc;

    private ListAppender<ILoggingEvent> appender;
    private Logger guardLogger;

    @BeforeEach
    void captureGuardLog() {
        guardLogger = (Logger) LoggerFactory.getLogger(AccessGuard.class);
        appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);
    }

    @AfterEach
    void releaseGuardLog() {
        guardLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("❗판단 불가와 정책 거부가 로그에서 갈린다 — 둘 다 응답은 같은 403 이다")
    void theTwoKindsOfDenialAreDistinguishable() throws Exception {
        String sid = sessionBySeller();

        // (1) 역할에 그랜트가 없다 — CUST 는 session:read 를 아예 안 받았다.
        mvc.perform(get("/sessions/{sid}", sid).with(user("cust-01").roles("CUST")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());

        // (2) 그랜트는 있는데 범위가 어긋난다 — 남의 세션이다.
        mvc.perform(get("/sessions/{sid}", sid).with(user("seller-02").roles("SELLER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());

        // (3) ❗**판단할 수 없다** — 세션이 없어서 귀속을 못 만든다. 앞의 둘과 성격이 다르다:
        // 앞 둘은 정책이 옳게 막은 것이고, 이건 정책이 답을 못 낸 것이다. 응답은 셋 다 403 이다.
        mvc.perform(get("/sessions/{sid}", "S-NOPE").with(user("seller-01").roles("SELLER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isForbidden());

        List<String> denials = messages();
        assertThat(denials)
                .as("세 거부 모두 사유와 함께 남아야 한다 — 남은 것: %s", denials)
                .hasSize(3);

        // ❗**사유 문면이 셋 다 달라야 한다.** 같은 문장이 반복되면 로그를 봐도 여전히 못
        // 가른다 — 사유를 남기는 목적 자체가 여기다. 세 요청의 HTTP 응답은 전부 같은 403 이다.
        assertThat(denials.stream().map(AccessDenialReasonTest::reasonOf).distinct().toList())
                .as("세 거부의 사유가 서로 달라야 한다 — 남은 것: %s", denials)
                .hasSize(3);

        assertThat(denials.get(0))
                .as("역할에 그랜트가 없는 거부")
                .contains("cust-01").contains("session:read").contains("그랜트가 없다");
        assertThat(denials.get(1))
                .as("범위가 어긋난 거부")
                .contains("seller-02").contains("자기 세션이 아니다");
        assertThat(denials.get(2))
                .as("판단 불가 — 막은 것이 아니라 답을 못 낸 것이다")
                .contains("seller-01").contains("판단할 수 없다");
    }

    @Test
    @DisplayName("❗사유는 응답에 실리지 않는다 — 실리면 세션 존재 여부를 응답으로 물어볼 수 있다")
    void theReasonNeverReachesTheClient() throws Exception {
        String sid = sessionBySeller();

        String body = mvc.perform(get("/sessions/{sid}", sid).with(user("seller-02").roles("SELLER")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("FORBIDDEN");
        assertThat(body)
                .as("정책 사유가 응답 본문에 새면 안 된다 — 본문: %s", body)
                .doesNotContain("자기 세션이 아니다")
                .doesNotContain("own_session")
                .doesNotContain("세션이 없다");

        // 그리고 로그에는 남아 있어야 한다 — 응답에서 뺀 것이 아예 사라진 것이 아니다.
        assertThat(messages()).singleElement().asString().contains("자기 세션이 아니다");
    }

    @Test
    @DisplayName("❗허용된 요청은 아무 줄도 남기지 않는다 — 거부만 남아야 신호가 된다")
    void allowedRequestsAreSilent() throws Exception {
        String sid = sessionBySeller();

        mvc.perform(get("/sessions/{sid}", sid).with(user("seller-01").roles("SELLER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        assertThat(messages())
                .as("허용까지 남기면 거부가 묻힌다")
                .isEmpty();
    }

    /** "… 사유=X" 에서 X 만. 행위자·action 이 달라서 줄 전체는 늘 다르다 — 사유를 봐야 한다. */
    private static String reasonOf(String line) {
        int i = line.indexOf("사유=");
        return i < 0 ? line : line.substring(i);
    }

    private List<String> messages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("접근 거부"))
                .toList();
    }

    /** seller-01 의 세션 하나. 만들면서 남는 로그는 여기서 걷어낸다. */
    private String sessionBySeller() throws Exception {
        String created = mvc.perform(post("/sessions").with(user("seller-01").roles("SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_SESSION))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        appender.list.clear();
        return JsonPath.read(created, "$.data.sessionId");
    }
}
