package com.sphinxfin.sphinx.api.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에러 코드의 단일 출처를 지킨다. 소유: 강희진
 *
 * PR #28 리뷰 ②의 원인이 "핸들러에 6번째 코드가 생겼는데 openapi는 5개 그대로"였다.
 * 프론트가 이 목록을 유니온 타입으로 들고 있어, 어긋나면 타입 검사에서 걸리거나 조용히
 * default로 떨어진다. 사람 눈 대신 이 테스트가 대조한다.
 *
 * <h2>세 번째 사본까지 본다 — CLAUDE.md</h2>
 *
 * <p>CLAUDE.md {@code api/} 절이 같은 목록을 들고 있고 스스로 <i>"이 목록은
 * contracts/openapi.yaml의 ApiError.code enum과 같아야 한다"</i>고 적어놓았다. 그런데 이 테스트가
 * 핸들러와 openapi만 봐서 <b>그 사본은 테스트 밖이었다</b> — 그래서 <b>같은 방식으로 세 번
 * 낡았다</b>(#67·#68에서 지적해 반영, #105에서 또 빠짐).
 *
 * <p>세 번 같은 방식으로 낡았으면 <b>사람이 기억하는 방식이 안 되는 것</b>이다. 이 파일이 이미
 * 소스와 yaml을 정규식으로 읽으므로 파일 하나 더 읽는 비용은 작다.
 *
 * <h2>❗네 번째 사본 — web 유니온 (이슈 #316)</h2>
 *
 * <p>위 문단이 <i>"프론트가 이 목록을 유니온 타입으로 들고 있어 어긋나면 조용히 default로
 * 떨어진다"</i> 를 이 테스트의 <b>존재 이유</b>로 적어 놓고, 정작 그 유니온은 안 봤다.
 * 그래서 <b>같은 방식으로 네 번째로 낡았다</b>.
 *
 * <pre>
 * 핸들러 13 · openapi 13 · CLAUDE.md 13 · web 10
 *   빠진 것: UNAUTHORIZED · FORBIDDEN · MEASUREMENT_INVALID
 * </pre>
 *
 * <p>{@code FORBIDDEN} 이 특히 나쁘다 — <b>기획서 7-4 역할 차단 시연이 내는 코드</b>인데
 * 화면이 타입으로 모르고 있었다. {@code MEASUREMENT_INVALID} 는 {@code #293} 으로 하루 전에
 * 들어온 새것이라, <b>코드를 더한 사람이 그 사본을 모른 채 지나간다</b>는 것을 그대로 보여준다.
 *
 * <p>위 문단이 <i>"세 번 같은 방식으로 낡았으면 사람이 기억하는 방식이 안 되는 것"</i> 이라고
 * 적었는데, 그 판단이 네 번째 사본에도 그대로 적용된다. {@code web/} 에는 테스트 러너가
 * 없으므로({@code 결정 10.59}) 여기서 본다.
 *
 * <p><b>상태 코드까지 본다.</b> CLAUDE.md는 {@code `CODE`(404)}로, openapi는
 * {@code - CODE  # 404}로 각각 상태를 적어둔다. 코드 이름만 맞고 상태가 어긋난 문서는
 * <b>빠진 항목보다 나쁘다</b> — 없는 것은 찾아보게 되는데 틀린 것은 그대로 믿는다.
 *
 * <h2>❗다섯 번째 사본 — web 문면 표</h2>
 *
 * <p>{@code web/src/lib/errorText.ts} 의 {@code Record<ErrorCode, string>} 이 코드마다
 * 사용자 문면을 하나씩 든다. 그 파일이 <b>전체 맵</b>인 이유를 스스로 적어 뒀다 —
 * 부분 맵이면 새 코드가 조용히 기본 문면으로 떨어지고 <i>"그게 화면에서는 정상처럼 보인다"</i>.
 *
 * <p>그래서 {@code tsc} 가 그 사본을 지킨다. <b>그런데 늦게 잡고, 못 잡는 갈래가 있다.</b>
 *
 * <p>❗<b>{@code main} 이 깨진 적은 없다</b>(PR #571 리뷰 실측 — 첫 부모 이력에서 두 파일이 다
 * 있는 154 커밋, 어긋남 0건). 코드를 더하면 네 번째 대조가 {@code web/src/api/types.ts} 도
 * 고치라고 요구하므로 CI 의 web 스텝({@code npm run build} = {@code tsc --noEmit &&
 * vite build})이 <b>반드시 돌고 머지 전에 막는다</b>. 그러니 이 대조가 값을 내는 자리는
 * 「깨지는 것」이 아니라 <b>빨개지는 시점과 갈래</b> 둘이다.
 *
 * <p><b>하나 — 시점.</b> 앞 네 대조가 초록이면 <i>"다 맞췄다"</i> 로 읽히고, 서버만 고치는
 * 사람은 {@code npm run build} 를 안 돌린다. 그래서 빨강을 CI 로그에서 만난다 —
 * {@code DOCUMENT_UNPROCESSABLE} 을 넣은 커밋({@code #527} 브랜치)에 <b>5분 뒤 고침 커밋</b>이
 * 붙은 것이 그 자리다. 여기서 보면 {@code ./gradlew test} 한 번에 같이 나온다.
 *
 * <p><b>둘 — 갈래.</b> 이 대조는 {@code tsc} 보다 <b>세다</b>. 누가 그 표를 {@code Partial<…>}
 * 이나 {@code Record<string, string>} 으로 느슨하게 바꾸면 {@code tsc} 는 그 순간부터
 * 아무것도 안 잡는데, 여기는 키 집합을 계약과 직접 맞추므로 그대로 잡는다.
 *
 * <p>❗그 갈래 때문에 {@code ci.yml} 의 {@code server_extra} 에도 이 파일이 들어가야 한다 —
 * <b>표만 느슨하게 바꾸는 변경은 {@code web/} 만 건드려서 server 잡이 아예 안 뜬다.</b>
 *
 * <h2>❗여섯 번째 사본 — 기능 명세서 §9</h2>
 *
 * <p>{@code docs/functional-spec-v1.2.md} §9 가 같은 목록을 <b>제출 문서</b>로 든다. 그리고
 * <b>같은 방식으로 낡았다</b> — {@code DOCUMENT_UNPROCESSABLE} 이 들어온 뒤에도
 * <i>"오류 코드 13종"</i> 이었고, 손으로 고쳤다({@code #583}).
 *
 * <p>위 문단들이 {@code CLAUDE.md} 를 대조에 넣은 근거가 <i>"세 번 같은 방식으로 낡았으면
 * 사람이 기억하는 방식이 안 되는 것"</i> 이다. 그 판단이 여기에도 그대로 걸린다.
 *
 * <h2>❗「N 벌」이라는 수도 본다</h2>
 *
 * <p>네 자리가 <i>"에러 코드 <b>여섯</b> 벌이 대조된다"</i> 로 <b>대조 대상의 개수</b>를
 * 적는다({@code CLAUDE.md} · {@code README.md} · 명세서 §9와 요약표 · <b>핸들러 javadoc</b>).
 * 그 수는 <b>이 테스트가 무엇을 보는지</b>에 달렸으므로, 사본을 하나 더 넣을 때마다 그
 * 자리들을 손으로 고쳐야 했다 — {@code #571}(다섯째)과 {@code #583} 이 두 PR 에 걸쳐 손으로
 * 맞췄다.
 *
 * <p>❗<b>핸들러는 나중에 들어왔고, 그 사이 낡아 있었다</b>(이슈 #618). 모집단이 문서 셋일
 * 때 그 파일의 javadoc 두 곳이 <i>"네 벌"</i> 로 남았다 — <b>코드를 더하는 사람이 제일 먼저
 * 여는 파일</b>이라, 거기서 맞출 곳이 넷이라고 읽으면 두 사본을 안 고치고 나간다. 모집단은
 * {@link #COUNT_CLAIMANTS} 에 있다.
 *
 * <p>그래서 그 수를 {@link #SOURCES} 에서 낸다. <b>사본을 늘리는 사람이 고칠 손 자리는 그
 * 목록 하나</b>이고, 고치면 그 네 자리가 빨개져 나머지를 알려준다. 목록과 실제로 읽는 파일이
 * 갈리는 것은 {@code theSourceListMatchesWhatIsActuallyRead} 가 막는다.
 *
 * <p>❗<b>주석을 무는 대조는 입력 선언이 있어야 돈다.</b> 주석만 바뀌면 바이트코드가 같아서
 * Gradle 이 {@code test} 를 UP-TO-DATE 로 건너뛴다 — 실측했다: 핸들러의 「여섯 벌」을
 * 「네 벌」로 되돌려도 {@code BUILD SUCCESSFUL} 이고 {@code --rerun-tasks} 로 강제해야
 * {@code FAILED} 다. 그래서 그 파일이 {@code build.gradle} 의 입력으로 선언돼 있다
 * (모듈 안 경로라 {@code ci.yml} 쪽은 {@code ^server/} 가 이미 잡는다).
 *
 * <p>❗<b>여기까지다 — 「테스트가 다 알아서 안다」는 아니다</b>(PR #586 리뷰 실측). 대조
 * 하나를 <b>통째로 지우면</b>({@code @Test} 삭제) 목록도 문서도 그대로라 <b>조용히 통과한다.</b>
 * 사본을 <b>빼는</b> 사람은 {@link #SOURCES} 를 같이 줄여야 하고, 그것을 무는 것은 없다.
 * 이 파일이 다루는 결함이 원래 «다른 사본을 모른 채 지나간다» 라, 그 방향이 남아 있다는
 * 사실을 적어 두는 것이 지금 할 수 있는 것이다.
 */
@DisplayName("에러 코드 계약 — 핸들러 ≡ openapi.yaml ≡ CLAUDE.md ≡ web 유니온 ≡ web 문면 ≡ 명세서 §9")
class ErrorCodeContractTest {

    private static final Path REPO_ROOT = Path.of("..");   // server/ 에서 실행된다

    /** 제출 문서의 API 절. v1.1 이 아니라 v1.2 다 — 구현 완료 시점의 명세다(CLAUDE.md). */
    private static final String SPEC = "docs/functional-spec-v1.2.md";

    private static final String HANDLER =
            "server/src/main/java/com/sphinxfin/sphinx/api/exception/GlobalExceptionHandler.java";
    private static final String CONTRACT = "contracts/openapi.yaml";
    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final String WEB_UNION = "web/src/api/types.ts";
    private static final String WEB_TEXT = "web/src/lib/errorText.ts";

    /**
     * 대조하는 사본 — 핸들러가 코드의 출처이자 첫째 사본이다.
     *
     * <p>❗<b>이 목록이 「N 벌」의 근거다.</b> 문서 셋이 그 수를 문면으로 적으므로, 사본을
     * 늘리는 사람은 <b>여기부터</b> 고친다. 그러면 {@link #COUNT_CLAIMANTS} 의 네 자리가
     * 빨개져서 나머지를 알려준다.
     */
    private static final List<String> SOURCES =
            List.of(HANDLER, CONTRACT, CLAUDE_MD, WEB_UNION, WEB_TEXT, SPEC);

    /** 이번 실행에서 {@link #read} 가 실제로 읽은 경로. {@code SOURCES} 와 맞춰 본다. */
    private final Set<String> readPaths = new TreeSet<>();
    private static final Pattern EMITTED = Pattern.compile("ApiError\\.of\\(\"([A-Z_]+)\"");

    /** CLAUDE.md의 {@code `CODE`(404)} 형식 — 코드와 상태를 함께 읽는다. */
    private static final Pattern CLAUDE_MD_ENTRY = Pattern.compile("`([A-Z_]+)`\\((\\d{3})\\)");

    /** web 유니온의 {@code | "CODE"  // 404 설명} 형식 — 코드와 상태를 함께 읽는다. */
    private static final Pattern WEB_UNION_ENTRY =
            Pattern.compile("\\|\\s*\"([A-Z_]+)\"\\s*(?://\\s*(\\d{3}))?");

    /** 명세서가 적는 코드 <b>개수</b> — {@code 「오류 코드 14종」}. 두 곳에 있다. */
    private static final Pattern SPEC_CODE_COUNT = Pattern.compile("오류 코드 (\\d+)종");

    /** 명세서 §9 의 {@code `CODE`} · {@code `CODE`(409)} 형식. 상태는 일부만 달려 있다. */
    private static final Pattern SPEC_ENTRY = Pattern.compile("`([A-Z_]+)`");

    /**
     * {@code 「N 벌」} — 문서·주석이 적는 <b>대조 대상 개수</b>.
     *
     * <p>❗<b>여기에 예시 숫자를 적지 않는다.</b> 이 패턴이 무는 것이 바로 그 수인데,
     * 설명문의 예시는 아무도 안 물어서 <b>수를 강제하는 코드의 설명이 그 수와 달라진다</b>
     * ({@code 「다섯 벌」} 로 적혀 있었다 — 이슈 #618). 실제 수는 {@link #COMPARED_SOURCES} 다.
     */
    private static final Pattern COPIES_CLAIM = Pattern.compile("(한|두|세|네|다섯|여섯|일곱|여덟)\\s*벌");

    /**
     * {@code 「N 벌」} 을 적는 자리 — <b>이 대조의 모집단</b>.
     *
     * <p>❗<b>핸들러가 여기 있어야 하는 자리였다</b>(이슈 #618). 모집단이 문서 셋이라
     * {@code GlobalExceptionHandler} 의 javadoc 두 곳이 <b>「네 벌」로 낡은 채 남아 있었다</b>
     * — 코드를 더하는 사람이 제일 먼저 여는 파일인데, 거기서 <i>"맞출 곳이 넷"</i> 을 읽으면
     * 두 사본을 안 고치고 나간다. 그 파일은 {@link #HANDLER} 로 <b>이미 읽는 입력</b>이라
     * 더하는 비용이 0 이다.
     *
     * <p><b>이 테스트 파일 자신은 넣지 않는다.</b> 여기에는 {@code 「한 벌만 만든다」}
     * (ADR-003) · {@code 「두 벌이 되면 갈린다」}(결정 스윕)를 <b>다른 주제의 예시로</b>
     * 인용한 주석이 있고, 그 옆에는 당연히 「에러 코드」가 적혀 있다 — 넣으면 정확한 인용이
     * 위반으로 잡힌다({@code #529} 가 밟은 거짓 양성과 같은 모양이다).
     *
     * <p>그래서 이 파일 쪽은 <b>낡을 숫자를 아예 없애는 것</b>으로 막는다 —
     * {@link #COPIES_CLAIM} 설명문에서 예시 수를 뺐다.
     *
     * <p>{@code decision-log.md} 도 넣지 않는다. 과거 결정을 적는 문서라 <b>그때 수가 옳다.</b>
     */
    private static final List<String> COUNT_CLAIMANTS =
            List.of("CLAUDE.md", "README.md", SPEC, HANDLER);

    /** 한글 수사 → 수. 문서가 숫자로 안 적고 낱말로 적는다. */
    private static final Map<String, Integer> NUMERALS = Map.of(
            "한", 1, "두", 2, "세", 3, "네", 4, "다섯", 5, "여섯", 6, "일곱", 7, "여덟", 8);

    /** 문서의 {@code 「N 벌」} 이 맞춰야 하는 수 — {@link #SOURCES} 에서 나온다. */
    private static final int COMPARED_SOURCES = SOURCES.size();

    /** 「N 벌」 앞뒤로 에러 코드 낱말을 찾는 창. 한 문장이 두 줄로 접히는 문서가 있다. */
    private static final int WINDOW = 200;

    /** web 문면 표의 {@code CODE: "…"} 형식 — 값(문면)은 이 대조의 관심이 아니다. */
    private static final Pattern ERROR_TEXT_ENTRY = Pattern.compile("(?m)^\\s*([A-Z_]+):");

    /** openapi enum의 {@code - CODE  # 404 설명} 형식. */
    private static final Pattern CONTRACT_ENTRY =
            Pattern.compile("-\\s+([A-Z_]+)\\s+#\\s*(\\d{3})");

    @Test
    @DisplayName("핸들러가 내보내는 코드 집합이 openapi ApiError.code enum과 같다")
    void handlerCodesMatchContract() throws Exception {
        assertThat(handlerCodes())
                .as("핸들러에 코드를 추가했으면 contracts/openapi.yaml의 ApiError.code enum에도 넣어야 한다")
                .isEqualTo(contractCodes());
    }

    @Test
    @DisplayName("CLAUDE.md의 코드 목록이 openapi enum과 같다")
    void claudeMdCodesMatchContract() throws Exception {
        assertThat(claudeMd().keySet())
                .as("CLAUDE.md api/ 절의 코드 목록이 계약과 어긋났다. 그 파일이 스스로 "
                        + "'openapi의 ApiError.code enum과 같아야 한다'고 적어둔 목록이다")
                .isEqualTo(contractStatuses().keySet());
    }

    @Test
    @DisplayName("CLAUDE.md의 상태 코드가 openapi 주석과 같다 — 틀린 상태는 빠진 항목보다 나쁘다")
    void claudeMdStatusesMatchContract() throws Exception {
        Map<String, String> contract = contractStatuses();
        Map<String, String> mismatched = new TreeMap<>();
        claudeMd().forEach((code, status) -> {
            String expected = contract.get(code);
            if (expected != null && !expected.equals(status)) {
                mismatched.put(code, status + " != " + expected);
            }
        });
        assertThat(mismatched)
                .as("CLAUDE.md가 적은 상태 코드가 계약과 다르다. 없는 항목은 찾아보게 되는데 "
                        + "틀린 항목은 그대로 믿는다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗web ErrorCode 유니온이 openapi enum과 같다 — 프론트가 분기하는 목록이다")
    void webUnionMatchesContract() throws Exception {
        assertThat(webUnion().keySet())
                .as("화면이 모르는 코드가 오면 조용히 default 문면으로 떨어진다. 코드를 "
                        + "더했으면 web/src/api/types.ts 의 ErrorCode 에도 넣는다 — "
                        + "그 파일이 없으면 %s 처럼 데모에서 보여줄 것을 화면이 모른다", "FORBIDDEN")
                .isEqualTo(contractStatuses().keySet());
    }

    @Test
    @DisplayName("❗web 유니온이 적은 상태 코드가 openapi 주석과 같다")
    void webUnionStatusesMatchContract() throws Exception {
        Map<String, String> contract = contractStatuses();
        Map<String, String> mismatched = new TreeMap<>();
        webUnion().forEach((code, status) -> {
            String expected = contract.get(code);
            if (expected != null && !status.isEmpty() && !expected.equals(status)) {
                mismatched.put(code, status + " != " + expected);
            }
        });
        assertThat(mismatched)
                .as("web 유니온 주석의 상태가 계약과 다르다 — 화면이 그 숫자로 분기를 짜면 "
                        + "타입은 통과하고 동작만 어긋난다")
                .isEmpty();
    }

    @Test
    @DisplayName("❗명세서 §9 의 코드 목록이 openapi enum과 같다 — 제출 문서다")
    void specCodesMatchContract() throws Exception {
        assertThat(specCodes())
                .as("docs/functional-spec-v1.2.md §9 가 든 코드 목록이 계약과 어긋났다. 그 문장은 "
                        + "제출 문서의 API 절이고, 실제로 DOCUMENT_UNPROCESSABLE 이 들어온 뒤에도 "
                        + "「13종」으로 남아 있었다(#583 이 손으로 고쳤다)")
                .isEqualTo(contractStatuses().keySet());
    }

    @Test
    @DisplayName("❗명세서 §9 의 「N종」이 실제 코드 수와 같다 — 목록만 고치고 수를 두면 조용히 틀린다")
    void specCodeCountMatchesContract() throws Exception {
        // ❗**모든 자리를 본다.** 이 문서는 그 수를 §9 문장과 요약표 두 곳에 적는다 —
        //   첫 매치만 보면 «§9 는 맞고 표는 틀린» 상태가 조용히 통과한다(실측으로 확인).
        Matcher m = SPEC_CODE_COUNT.matcher(read(SPEC));
        List<Integer> claimed = new ArrayList<>();
        while (m.find()) {
            claimed.add(Integer.parseInt(m.group(1)));
        }
        assertThat(claimed)
                .as("명세서에서 「오류 코드 N종」을 하나도 못 찾았다 — 문면이 바뀌었으면 이 "
                        + "대조도 같이 고친다. 안 그러면 조용히 통과한다")
                .isNotEmpty();
        assertThat(claimed)
                .as("명세서가 적은 코드 수가 실제와 다르다 — 목록에 하나를 더하고 수를 안 고치면 "
                        + "세어 보는 사람만 알아챈다. 이 문서는 그 수를 두 곳에 적는다")
                .containsOnly(contractCodes().size());
    }

    @Test
    @DisplayName("❗문서가 적은 「N 벌」이 이 테스트가 보는 출처 수와 같다 — 사본을 늘리면 여기가 알려준다")
    void everyDocumentClaimsTheRightNumberOfCopies() {
        Map<String, Integer> wrong = new TreeMap<>();
        for (String doc : COUNT_CLAIMANTS) {
            String text = read(doc);
            Matcher m = COPIES_CLAIM.matcher(text);
            int found = 0;
            int skipped = 0;
            while (m.find()) {
                // ❗에러 코드 이야기만 본다. 이 레포는 「한 벌만 만든다」(ADR-003 해시 기반) ·
                //   「두 벌이 되면 갈린다」(결정 스윕)처럼 같은 낱말을 다른 주제에도 쓴다 —
                //   통째로 세면 그것들이 전부 오답으로 잡힌다.
                if (!mentionsErrorCodes(text, m.start())) {
                    skipped++;
                    continue;
                }
                found++;
                Integer claimed = NUMERALS.get(m.group(1));
                if (claimed != null && claimed != COMPARED_SOURCES) {
                    wrong.put(doc + ": " + m.group(), claimed);
                }
            }
            // ★ 0건이면 아무것도 안 재고 통과한다 — 그 파일이 그 수를 적는 것이 전제다.
            assertThat(found).as("%s 에서 에러 코드의 「N 벌」 문면을 못 찾았다 — 문면이 "
                    + "바뀌었으면 이 대조도 같이 고친다", doc).isPositive();
            // ❗**핸들러에서는 건너뛴 것이 있으면 안 된다.** 위 창 규칙은 앞뒤 200 자에
            //   에러 코드 낱말이 있는지로 가르는 어림이라, 낱말 없이 적은 「N 벌」은 조용히
            //   안 세어진다 — 그 파일에 다른 주장이 이미 하나라도 있으면 위 단정도 통과한다.
            //   문서 셋은 주제가 섞여 있어 그 어림을 받아들이지만, 이 파일은 **에러 코드를
            //   만드는 자리**이므로 여기서 「벌」은 사본 수여야 한다. 다른 뜻으로 쓸 일이
            //   생기면 그 문장을 다른 말로 적는다(이슈 #618 에서 한 줄을 그렇게 고쳤다).
            if (doc.equals(HANDLER)) {
                assertThat(skipped)
                        .as("%s 의 「N 벌」 중 %d 개가 에러 코드 이야기로 안 읽혀 대조에서 "
                                + "빠졌다. 사본 수를 말하는 것이면 앞뒤에 「에러 코드」를 "
                                + "적어 세어지게 하고, 다른 뜻이면 「벌」을 쓰지 않는다", doc, skipped)
                        .isZero();
            }
        }
        assertThat(wrong)
                .as("문서가 적은 대조 사본 수가 실제(%d)와 다르다. 이 대조가 보는 것은 «수» "
                        + "하나다 — 괄호 안 목록은 안 본다(문서마다 표기가 달라 값에 비해 "
                        + "비싸다). 수를 고칠 때 그 목록도 같이 고친다", COMPARED_SOURCES)
                .isEmpty();
    }

    @Test
    @DisplayName("★ SOURCES 가 실제로 읽히는 파일과 같다 — 「N 벌」의 근거가 목록이라서 먼저 잰다")
    void theSourceListMatchesWhatIsActuallyRead() throws Exception {
        // 여섯 접근자를 다 부른다. 하나가 다른 파일을 읽거나, 목록에만 있고 아무도 안 읽는
        // 파일이 생기면 아래 대조가 그것을 든다.
        handlerCodes();
        contractStatuses();
        claudeMd();
        webUnion();
        errorTextCodes();
        specCodes();

        assertThat(readPaths)
                .as("SOURCES 와 실제로 읽은 파일이 다르다. 「N 벌」 대조가 이 목록의 크기를 "
                        + "쓰므로, 목록에 넣고 읽는 코드를 안 쓰면 문서의 수만 올라간다")
                .containsExactlyInAnyOrderElementsOf(SOURCES);
    }

    @Test
    @DisplayName("❗web 문면 표(errorText.ts)가 계약의 코드를 전부 든다 — 빠지면 웹 빌드만 깨진다")
    void errorTextCoversContract() throws Exception {
        assertThat(errorTextCodes())
                .as("코드를 더했으면 web/src/lib/errorText.ts 에 사용자 문면도 적는다. "
                        + "여기까지 같이 보는 이유는 순서다 — 안 보면 위 네 대조가 초록인 채로 "
                        + "웹 빌드만 빨개지고, 서버만 고친 사람은 그걸 모른 채 머지한다")
                .isEqualTo(contractStatuses().keySet());
    }

    /** 그 「N 벌」이 에러 코드 이야기인가 — 앞뒤 창에 에러 코드 낱말이 있으면 그렇다. */
    private boolean mentionsErrorCodes(String text, int at) {
        int from = Math.max(0, at - WINDOW);
        int to = Math.min(text.length(), at + WINDOW);
        String window = text.substring(from, to);
        return window.contains("ErrorCode") || window.contains("에러 코드")
                || window.contains("오류 코드");
    }

    /** 명세서 §9 문장의 코드 집합. */
    private Set<String> specCodes() {
        String src = read(SPEC);
        int from = src.indexOf("오류 코드");
        assertThat(from)
                .as("%s 에서 「오류 코드 …」 문장을 못 찾았다 — 문면이 바뀌었으면 이 대조도 "
                        + "같이 고친다. 안 고치면 조용히 통과한다", SPEC)
                .isNotNegative();
        // 그 문장 하나만 본다 — 문서 전체를 훑으면 다른 절의 백틱 대문자(상태·enum 값)가 섞인다.
        int end = src.indexOf('\n', from);
        String sentence = src.substring(from, end < 0 ? src.length() : end);

        Set<String> out = new TreeSet<>();
        Matcher m = SPEC_ENTRY.matcher(sentence);
        while (m.find()) {
            out.add(m.group(1));
        }
        assertThat(out).as("§9 문장에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return out;
    }

    /** web 문면 표의 코드 집합. */
    private Set<String> errorTextCodes() {
        String src = read(WEB_TEXT);
        int from = src.indexOf("const ERROR_TEXT");
        assertThat(from)
                .as("web/src/lib/errorText.ts 에서 ERROR_TEXT 표를 못 찾았다 — 이름이 바뀌었으면 "
                        + "이 대조도 같이 고친다. 안 고치면 조용히 통과한다")
                .isNotNegative();
        int end = src.indexOf("\n};", from);
        assertThat(end).as("ERROR_TEXT 표의 끝을 못 찾았다 — 형식이 바뀐 것이다").isNotNegative();
        String table = src.substring(from, end);

        Set<String> out = new TreeSet<>();
        Matcher m = ERROR_TEXT_ENTRY.matcher(table);
        while (m.find()) {
            out.add(m.group(1));
        }
        assertThat(out).as("표에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return out;
    }

    /** web ErrorCode 유니온의 코드 → 주석에 적힌 상태(없으면 빈 문자열). */
    private Map<String, String> webUnion() {
        String src = read(WEB_UNION);
        int from = src.indexOf("export type ErrorCode");
        assertThat(from)
                .as("web/src/api/types.ts 에서 ErrorCode 유니온을 못 찾았다 — 타입 이름이 "
                        + "바뀌었으면 이 대조도 같이 고친다. 안 고치면 조용히 통과한다")
                .isNotNegative();
        String union = src.substring(from, src.indexOf(';', from));

        Map<String, String> out = new TreeMap<>();
        Matcher m = WEB_UNION_ENTRY.matcher(union);
        while (m.find()) {
            out.put(m.group(1), m.group(2) == null ? "" : m.group(2));
        }
        assertThat(out).as("유니온에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return out;
    }

    /** CLAUDE.md api/ 절의 코드 → 상태. */
    private Map<String, String> claudeMd() {
        Map<String, String> out = new TreeMap<>();
        Matcher m = CLAUDE_MD_ENTRY.matcher(read(CLAUDE_MD));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        assertThat(out).as("CLAUDE.md에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return out;
    }

    /** openapi enum의 코드 → 주석에 적힌 상태. */
    private Map<String, String> contractStatuses() throws Exception {
        Map<String, String> out = new TreeMap<>();
        Matcher m = CONTRACT_ENTRY.matcher(read(CONTRACT));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        assertThat(out.keySet())
                .as("enum 주석에서 읽은 코드 집합이 enum 값과 다르다 — 주석 형식이 바뀐 것이다")
                .isEqualTo(contractCodes());
        return out;
    }

    private String read(String relative) {
        try {
            readPaths.add(relative);
            return Files.readString(REPO_ROOT.resolve(relative));
        } catch (Exception e) {
            throw new IllegalStateException("읽을 수 없다: " + relative, e);
        }
    }

    /** GlobalExceptionHandler가 실제로 내보내는 코드. */
    private Set<String> handlerCodes() {
        String src = read(HANDLER);
        Set<String> codes = new TreeSet<>();
        Matcher m = EMITTED.matcher(src);
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes).as("핸들러에서 코드를 하나도 못 읽었다면 이 테스트의 정규식이 낡은 것이다")
                .isNotEmpty();
        return codes;
    }

    /** 계약이 선언한 코드. */
    private Set<String> contractCodes() throws Exception {
        JsonNode spec = new ObjectMapper(new YAMLFactory())
                .readTree(REPO_ROOT.resolve("contracts/openapi.yaml").toFile());
        JsonNode enumNode = spec.at("/components/schemas/ApiError/properties/code/enum");
        assertThat(enumNode.isArray()).as("openapi ApiError.code에 enum이 있어야 한다").isTrue();
        Set<String> codes = new TreeSet<>();
        enumNode.forEach(n -> codes.add(n.asText()));
        return codes;
    }
}
