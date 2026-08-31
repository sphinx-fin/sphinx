package com.sphinxfin.sphinx.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /sessions/{sid}/simulate} 의 접근통제 배선 (이슈 #214). 소유: 강희진
 *
 * <h2>정책 층 단정으로는 부족하다</h2>
 *
 * <p>{@code AccessPolicyTest} 가 {@code session:simulate} 의 그랜트를 잰다(#222). 그건
 * <i>"정책이 무엇을 허용하는가"</i> 이고, 이 파일은 <b>그 정책이 이 엔드포인트에 실제로
 * 걸려 있는가</b>를 잰다 — 어노테이션을 지워도 정책 테스트는 전부 초록이다.
 *
 * <p>{@code enforce=true} 로 돌린다. 기본값에서는 {@link com.sphinxfin.sphinx.security.AccessGuard}
 * 가 정책을 아예 안 부르므로 이 파일이 재려는 것을 못 잰다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sphinx.security.enforce=true")
@DisplayName("F-SIM-001 시뮬레이터 접근통제 배선 (enforce=true, 이슈 #214)")
class SimulateAccessWiringTest {

    private static final String BODY = """
            {"productId":"doc-els-kiwoom-4181","channel":"FACE_TO_FACE","ageBand":"60대"}""";
    private static final String AMOUNT = """
            {"amount":50000000}""";

    @Autowired private MockMvc mvc;

    private static RequestPostProcessor seller(String id) { return user(id).roles("SELLER"); }

    @Test
    @DisplayName("❗면담을 진행하는 SELLER 가 자기 세션에서 돌린다 — 데모 경로")
    void theSellerRunsItOnTheirOwnSession() throws Exception {
        String sid = sessionBy("seller-01");

        mvc.perform(post("/sessions/{sid}/simulate", sid).with(seller("seller-01"))
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarios").exists());
    }

    @Test
    @DisplayName("❗남의 세션에서는 못 돌린다 — 어노테이션을 지우면 이것만 깨진다")
    void notOnSomeoneElsesSession() throws Exception {
        String sid = sessionBy("seller-01");

        // 허용만 거는 단정으로는 과허용 변이가 안 잡힌다. 무서운 쪽은 덜 허용하는 변이가
        // 아니라 더 허용하는 변이다 — 어노테이션이 없으면 이 줄이 200 이 된다.
        mvc.perform(post("/sessions/{sid}/simulate", sid).with(seller("seller-02"))
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗MGR 은 지점 세션을 읽어도 시뮬레이터는 못 돌린다 — #222 의 판단이 HTTP 에서도 선다")
    void theBranchManagerCannotRunIt() throws Exception {
        String sid = sessionBy("seller-01");

        mvc.perform(post("/sessions/{sid}/simulate", sid).with(user("mgr-01").roles("MGR"))
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("❗없는 세션은 403 이다 — enforce 아래서는 정책이 먼저 막는다")
    void unknownSessionIsBlocked() throws Exception {
        // 시뮬레이션 자체는 (금액, 조건, 지수 스냅샷)의 순수 함수라 세션을 안 읽어도 답이
        // 나온다(P2). 그래서 sessionService.get(sid) 이 없으면 200 이 나가고,
        // AuditInterceptor 가 **세션 기록과 대응하지 않는 감사 항목**을 만든다(#222).
        //
        // enforce=true 라 정책이 먼저 도는데, 없는 세션은 ownerId 가 null 이라
        // own_session 이 "판단할 수 없다"로 거부한다 — 그래서 여기서 보이는 것은 403 이다.
        // 존재 여부를 알려주지 않는 쪽이라 그게 맞다(#156 과 같은 결).
        mvc.perform(post("/sessions/{sid}/simulate", "S-NOPE").with(seller("seller-01"))
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isForbidden());
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    // 바깥 클래스의 enforce=true 를 덮는다 — @Nested 는 바깥 설정을 물려받는다.
    @TestPropertySource(properties = "sphinx.security.enforce=false")
    @DisplayName("로컬(enforce=false) — 정책이 안 도는 자리")
    class WithoutEnforcement {

        @Autowired private MockMvc mvc;

        /**
         * ❗{@code enforce=false} 면 {@code @PreAuthorize} 가 통과시키므로, 없는 세션을 막는
         * 것은 <b>{@code sessionService.get(sid)} 하나뿐</b>이다. 그 줄이 없으면 200 이 나가고
         * {@code AuditInterceptor} 가 <b>세션 기록과 대응하지 않는 감사 항목</b>을 만든다(#222).
         *
         * <p>위 {@code enforce=true} 단정으로는 이걸 못 잡는다 — 거기서는 정책이 먼저 403 을
         * 내므로 세션 조회를 지워도 답이 안 바뀐다. 실측했다.
         */
        @Test
        @DisplayName("❗없는 세션은 404 다 — 정책이 안 도는 자리에서는 이 줄만 막는다")
        void unknownSessionIsNotFound() throws Exception {
            mvc.perform(post("/sessions/{sid}/simulate", "S-NOPE")
                            .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                    .andExpect(status().isNotFound());
        }
    }

    private String sessionBy(String sellerId) throws Exception {
        String created = mvc.perform(post("/sessions").with(seller(sellerId))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(created, "$.data.sessionId");
    }
}
