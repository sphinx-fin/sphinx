/**
 * S-00 화면 목록 (심사·시연용 목차). 소유: 오준서.
 *
 * ── 왜 이 화면이 필요한가 ────────────────────────────────────────────────────
 *
 * 화면 7개 중 **5개가 세션ID 를 경로에 받는다**(`/interview/:sid` 등). 그래서 주소만으로는
 * 못 닿고, 닿는 유일한 길이 S-02 에서 세션을 만들어 그 화면의 버튼을 누르는 것이었다.
 * 심사자가 직접 눌러 보는 자리에서는 그게 막힌다 — 세션을 안 만들면 절반이 안 열리고,
 * 만들어도 S-06(적색 승인)·S-07(리포트)처럼 **특정 상태에서만 뜻이 있는 화면**은 왜 비어
 * 보이는지 알 수 없다. 그 두 가지를 한 화면이 같이 해결한다: 세션을 하나 만들어 들고
 * 있고, 각 화면이 무엇을 전제하는지 카드에 적는다.
 *
 * ── ❗이건 RBAC 의 ADMIN 화면이 아니다 ──────────────────────────────────────
 *
 * 이름이 겹쳐서 위험한 자리다. 이 레포에서 *관리자 화면*은 **S-06**(적색 승인, MGR)이고
 * `Role.ADMIN` 은 계정·상품 등록을 맡는 **시스템 관리자**다(ADR-001). 이 화면은 그 둘 중
 * 어느 것도 아니다 — **서버를 한 번도 권한으로 묻지 않는 클라이언트 목차**다.
 *
 * 그래서 여기 링크가 있다는 것이 **권한이 있다는 뜻이 아니다.** 목차에 있는 화면을 열어도
 * 그 화면의 API 호출은 그대로 `rbac_policy.yaml` 을 통과해야 한다. 이 문장을 화면에도
 * 적어 둔다 — 심사에서 "목차에서 열리니 권한이 없는 것 아니냐" 로 읽히면 안 된다.
 *
 * ── 권한 차단(기획 7-4)은 이 목차로 시연되지 않는다 ─────────────────────────
 *
 * alpha 는 **개방 모드**라 `docker-entrypoint.d/15-demo-mode.sh` 가 nginx 에서 경로별로
 * 데모 계정을 주입한다 — `/api/dashboard/` 는 compl-01, 오버라이드 승인은 mgr-01, 나머지는
 * seller-01. 즉 여기서 S-08 을 열면 **차단 화면이 아니라 대시보드가 뜬다.** 그게 정상이고,
 * SELLER→집계 차단은 화면이 아니라 API 로 보여야 한다. 목차가 "차단이 없다"로 읽히지
 * 않도록 화면 아래에 그대로 적는다.
 *
 * ── 세션 만들기를 여기 둔 이유와, 두지 않은 것 ──────────────────────────────
 *
 * 심사자가 S-03 에 닿으려면 설문 6문항을 손으로 채워야 한다. 그 마찰만 없애려고
 * `DEMO_SURVEY_ANSWERS`(기획 7-2 ①번의 응답 조합)로 세션 하나를 만든다. **연출이 아니다** —
 * 입력을 채울 뿐이고 적합/부적합도 신호등도 서버가 정한다(P1).
 *
 * 두 가지는 **일부러 안 한다.**
 *
 *   · **만든 뒤 인터뷰로 자동 이동하지 않는다.** S-02 의 설계 판단 ④ 와 같은 이유다 —
 *     세션을 연 화면이 그대로 고객 화면이 되면 판매자가 앉은 자리에서 대필하는 흐름이
 *     자연스러워진다(F-INT-003). 여기서는 세션 번호를 받아 들고 멈춘다.
 *   · **상품을 고르게 하지 않는다.** 그건 S-02 의 일이다. 목차가 상품 선택·설문·PII 경고를
 *     다시 갖기 시작하면 S-02 가 두 벌이 되고, 그 두 벌은 갈린다. 첫 상품으로 만들고
 *     "골라 만들려면 S-02" 를 적는다.
 *
 * ── 화면 목록은 이 파일의 `SCREENS` 하나다 ──────────────────────────────────
 *
 * 라우트 표(`App.tsx`)와 이 목록이 두 벌이라 갈릴 수 있다. 웹에는 테스트 러너가 없어
 * (결정 10.59) 대조를 자동화할 자리가 없으므로, 화면을 새로 붙이는 사람이 여기도 고치게
 * **`App.tsx` 의 라우트 주석에서 이 파일을 지목해 둔다.**
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { get, post } from "../api/client";
import type {
  CreateSessionRequest,
  OverrideStatus,
  ProductSummary,
  SessionResponse,
  SessionState,
} from "../api/types";
import ErrorNote from "../components/ErrorNote";
import { useElderlyMode } from "../hooks/useElderlyMode";
import { describeError, type ShownError } from "../lib/errorText";
import { DEMO_SURVEY_ANSWERS, SURVEY_SCHEMA_VERSION, toSurveyResult } from "../lib/survey";
import "./S00_Admin.css";

/** 세션ID 를 기억하는 자리. 심사자가 탭을 옮겨 다녀도 목차가 세션을 들고 있어야 한다.
 *
 *  `sessionStorage` 가 아니라 `localStorage` 인 이유는 `lib/reexplain` 과 정반대다 — 저기
 *  담기는 것은 **고객 발화에 붙는 재설명 문면**이라 창을 닫으면 사라져야 하고, 여기 담기는
 *  것은 심사자가 방금 만든 **세션 번호 하나**다. 리허설 중 브라우저를 닫았다 열 때 번호를
 *  다시 받아오지 못하면(목록 조회 엔드포인트가 계약에 없다) 세션을 새로 만들어야 한다.
 *
 *  `export` 인 이유는 `pages/Guide.tsx` 가 같은 번호를 읽기 때문이다. 가이드의 "이 화면
 *  열어보기" 는 여기서 만든 세션으로 가야 하고, 키 문자열을 저쪽에 다시 적으면 두 벌이
 *  된다 — 갈리는 날 링크만 조용히 다른 세션(또는 없는 세션)을 연다. */
export const SID_KEY = "sphinx.admin.sessionId";

function readStoredSid(): string {
  try {
    return localStorage.getItem(SID_KEY) ?? "";
  } catch {
    return "";   // 사생활 모드·저장소 차단 — 목차는 그대로 뜨고 세션만 손으로 넣는다
  }
}

/** 카드에 붙는 대상 표기. 창구에서 **누가 이 화면을 잡는가** 다 — 역할(RBAC)과 다르다. */
type Audience = "고객" | "판매자" | "관리자" | "준법감시" | "운영";

interface Screen {
  /** 명세 8절의 화면 번호. */
  id: string;
  name: string;
  audience: Audience;
  /** 라우트. `:sid` 가 있으면 세션이 있어야 열린다. */
  route: string;
  /** 무엇을 보는 화면인가 — 한 줄. */
  note: string;
  /** 이 화면이 **뜻을 갖는 조건**. 비어 보이는 화면 앞에서 심사자가 멈추지 않게 적는다. */
  prereq?: string;
}

/**
 * 명세 8절 S-02~S-08. **S-01(문서 업로드)은 빼 둔다** — 이번 라운드 범위 밖이라
 * (#406) 목차에 두면 열리는데 실제 배선이 아닌 화면을 심사자가 먼저 만난다.
 * 순서는 화면 번호 순이고 데모 흐름 순이 아니다 — 심사자가 명세를 들고 대조한다.
 */
const SCREENS: readonly Screen[] = [
  {
    id: "S-02",
    name: "세션 시작",
    audience: "판매자",
    route: "/",
    note: "상품과 고객 정보를 넣어 세션을 만들어요.",
    prereq: "이름·전화번호를 넣는 칸은 없어요.",
  },
  {
    id: "S-03",
    name: "되말하기 인터뷰",
    audience: "고객",
    route: "/interview/:sid",
    note: "고객이 자기 말로 다시 설명해요.",
    prereq: "판정을 확정한 세션에서는 질문이 나오지 않아요.",
  },
  {
    id: "S-04",
    name: "손실 시뮬레이터",
    audience: "고객",
    route: "/simulator/:sid",
    note: "최악·중간·최선을 금액으로 나란히 보여줘요.",
    prereq: "못 읽어낸 항목이 하나라도 있으면 계산하지 않아요.",
  },
  {
    id: "S-05",
    name: "판정 결과",
    audience: "판매자",
    route: "/judgment/:sid",
    note: "항목별 등급과 근거를 보고, 재설명을 시작해요.",
    prereq: "답변이 있어야 판정할 수 있어요. 확정하면 되돌릴 수 없어요.",
  },
  {
    id: "S-06",
    name: "예외 승인",
    audience: "관리자",
    route: "/override/:sid",
    note: "판매자가 사유를 적어 요청하고, 관리자가 승인해요.",
    prereq: "신호가 빨간 세션에서만 쓸 수 있어요.",
  },
  {
    id: "S-07",
    name: "이해 기록 리포트",
    audience: "판매자",
    route: "/report/:sid",
    note: "교부 문서의 내용해시와 발행 이력을 봐요.",
    prereq: "아직 발행 전이면 「발행하기」가 보여요.",
  },
  {
    id: "S-08",
    name: "오해 지도 대시보드",
    audience: "준법감시",
    route: "/dashboard",
    note: "항목별·지점별 오해율과 결정 요약을 봐요.",
    prereq: "세션 없이 열 수 있어요. 개인 정보는 나오지 않아요.",
  },
];

/** 세션 상태 라벨. **전체 맵**이라 `SessionState` 에 값이 늘면 컴파일이 깨진다
 *  (`lib/errorText` 의 `Record<ErrorCode, …>` 와 같은 이유 — web 에는 테스트 러너가 없다). */
const STATE_LABEL: Record<SessionState, string> = {
  CREATED: "생성됨 — 아직 아무것도 묻지 않았다",
  IN_PROGRESS: "면담 중",
  RE_EXPLAIN: "재설명 중",
  RE_VERIFY: "재검증 중",
  JUDGED: "판정 확정 — 되돌릴 수 없다",
  CLOSED: "종료",
  ABORTED: "중단",
};

const OVERRIDE_LABEL: Record<OverrideStatus, string> = {
  NONE: "요청 없음",
  PENDING_APPROVAL: "승인 대기",
  APPROVED: "승인됨",
};

export default function S00Admin() {
  const navigate = useNavigate();
  const { elderly, toggle } = useElderlyMode();

  const [sid, setSid] = useState(readStoredSid);
  /** 조회로 확인된 세션. 손으로 넣은 번호가 실재하는지, 지금 어느 상태인지 여기서만 안다. */
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [busy, setBusy] = useState<"none" | "creating" | "loading">("none");
  const [error, setError] = useState<ShownError | null>(null);
  /** 방금 만든 세션의 상품 표기. 만들 때만 알 수 있다(`SessionResponse` 는 productId 만 준다). */
  const [createdWith, setCreatedWith] = useState<string | null>(null);

  const trimmed = sid.trim();

  useEffect(() => {
    try {
      if (trimmed) localStorage.setItem(SID_KEY, trimmed);
      else localStorage.removeItem(SID_KEY);
    } catch {
      /* 저장 실패는 이 화면 동작에 영향 없음 — 이번 탭에서는 그대로 쓴다 */
    }
  }, [trimmed]);

  /** 세션 번호가 바뀌면 앞 세션의 상태를 들고 있으면 안 된다 — 그대로 두면 다른 세션의
      상태를 보고 어느 화면이 뜻이 있는지 판단한다. */
  useEffect(() => {
    setSession((prev) => (prev && prev.sessionId === trimmed ? prev : null));
  }, [trimmed]);

  const lookup = useCallback(async (target: string) => {
    setBusy("loading");
    setError(null);
    try {
      setSession(await get<SessionResponse>(`/sessions/${encodeURIComponent(target)}`));
    } catch (e) {
      setSession(null);
      setError(describeError(e));
    } finally {
      setBusy("none");
    }
  }, []);

  /**
   * 데모 세션 하나를 만든다.
   *
   * 속성은 `scripts/walk_demo_session.sh` 2단계와 같은 조합이다 — **60대 + 5천만원대**가
   * 취약 가중 정확히 임계값 4 라서(결정 10.12), 이 조합을 흐트리면 고령자 모드가 안 켜지고
   * 재설명이 일반 문면으로 나간다. 에러도 로그도 없이 시연만 밋밋해지는 종류다.
   */
  async function createDemoSession() {
    setBusy("creating");
    setError(null);
    setCreatedWith(null);
    try {
      const products = await get<ProductSummary[]>("/products");
      const product = products?.[0];
      if (!product) {
        setError({
          text: "등록된 상품이 없어요.",
          detail: null,
        });
        return;
      }
      const body: CreateSessionRequest = {
        productId: product.productId,
        channel: "FACE_TO_FACE",
        ageBand: "60대",
        experienceLevel: "없음",
        amountBand: "5천만원대",
        contractRef: null,
        surveySchemaVersion: SURVEY_SCHEMA_VERSION,
        surveyResult: toSurveyResult(DEMO_SURVEY_ANSWERS),
      };
      const created = await post<SessionResponse>("/sessions", body);
      setSid(created.sessionId);
      setSession(created);
      setCreatedWith(product.name);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy("none");
    }
  }

  const working = busy !== "none";

  return (
    <main className="adm">
      <div className="adm__shell">
        <header className="adm__head">
          <p className="adm__eyebrow">심사·시연용 목차</p>
          <h1 className="adm__title">화면 목록</h1>
          <p className="adm__lede">
            명세 8절의 화면 7개예요.
          </p>
          <div className="adm__headrow">
            <button
              type="button"
              role="switch"
              aria-checked={elderly}
              className={`adm__toggle ${elderly ? "adm__toggle--on" : ""}`}
              onClick={toggle}
            >
              고령자 모드 {elderly ? "켜짐" : "꺼짐"}
            </button>
            <p className="adm__hint">
              전 화면에 적용돼요. 고객 화면에는 토글도 있어요.
            </p>
            {/* 목차는 **어디로 가는지**만 말한다. 가서 무엇을 하는지는 가이드가 맡는다 —
                그 둘을 한 화면에 합치면 카드마다 설명이 길어져 목차 구실을 못 한다. */}
            <Link className="adm__guidelink" to="/guide">
              사용 가이드
            </Link>
          </div>
        </header>

        {/* ── 세션 ─────────────────────────────────────────────────────────── */}
        <section className="adm__card" aria-labelledby="adm-session">
          <h2 className="adm__section" id="adm-session">
            세션
          </h2>
          <p className="adm__hint adm__hint--block">
            <b>5개는 세션 번호가 필요해요.</b> S-02·S-08 은 없어도 열려요.
          </p>

          <div className="adm__sidrow">
            <div className="adm__field">
              <label className="adm__label" htmlFor="adm-sid">
                세션 번호
              </label>
              <input
                id="adm-sid"
                className="adm__control"
                value={sid}
                disabled={working}
                placeholder="예: 3f9a1c7e-…"
                onChange={(e) => setSid(e.target.value)}
              />
            </div>
            <button
              type="button"
              className="adm__btn adm__btn--ghost"
              disabled={working || trimmed === ""}
              onClick={() => void lookup(trimmed)}
            >
              {busy === "loading" ? "조회 중…" : "상태 조회"}
            </button>
          </div>

          <div className="adm__actions">
            <button
              type="button"
              className="adm__btn adm__btn--primary"
              disabled={working}
              onClick={() => void createDemoSession()}
            >
              {busy === "creating" ? "만드는 중…" : "데모 세션 만들기"}
            </button>
            <p className="adm__hint">
              60대·5천만 원대·투자경험 없음으로 세션을 만들어요. 상품을 고르려면
              S-02 로 가세요.
            </p>
          </div>

          {error && (
            <ErrorNote error={error} className="adm__alert adm__alert--error" title="세션을 확인하지 못했어요" />
          )}

          {session && (
            <div className="adm__state" aria-live="polite">
              <p className="adm__state-id">
                <code>{session.sessionId}</code>
              </p>
              <dl className="adm__state-grid">
                <div>
                  <dt>상태</dt>
                  <dd>{STATE_LABEL[session.state]}</dd>
                </div>
                <div>
                  <dt>예외 승인</dt>
                  <dd>{OVERRIDE_LABEL[session.overrideStatus]}</dd>
                </div>
                <div>
                  <dt>상품</dt>
                  <dd>
                    <code>{session.productId}</code>
                    {createdWith && <> · {createdWith}</>}
                  </dd>
                </div>
              </dl>
              {/* 신호등은 여기서 말하지 않는다. 그 값은 `/gate-preview`·`/judge` 가 소유하고
                  (P1) S-05 가 근거와 함께 그린다 — 목차가 색만 옮겨 적으면 근거 없는 판정을
                  화면 하나 더에서 말하는 것이 된다(P4). 대신 상태로 갈 곳을 안내한다. */}
              <p className="adm__hint">
                신호등과 근거는 <b>S-05</b> 에서 봐요.
              </p>
            </div>
          )}
        </section>

        {/* ── 화면 ─────────────────────────────────────────────────────────── */}
        <section className="adm__screens" aria-label="화면 목록">
          {SCREENS.map((s) => (
            <ScreenCard
              key={s.id}
              screen={s}
              sid={trimmed}
              onOpen={(path) => navigate(path)}
            />
          ))}
        </section>

        {/* ── 심사자가 알아야 하는 것 ──────────────────────────────────────── */}
        <section className="adm__card adm__card--note" aria-labelledby="adm-notes">
          <h2 className="adm__section" id="adm-notes">
            이 목차로는 보이지 않는 것
          </h2>
          <ul className="adm__list">
            <li>
              <b>권한 차단은 API 로 확인해요.</b> 심사용 배포는 로그인 없이 열려요.
            </li>
            <li>
              <b>등급·신호등·금액·집계는 모두 서버가 만들어요.</b>
            </li>
            <li>
              <b>합성 세션은 리포트가 비어 있어요.</b> 위에서 만든 세션으로 보세요.
            </li>
          </ul>
        </section>
      </div>
    </main>
  );
}

/**
 * 화면 한 장.
 *
 * **열기 두 개를 두는 이유**: 같은 탭 이동은 뒤로 가기로 목차에 돌아올 수 있어 훑기에
 * 좋고, 새 탭은 S-02→S-03 처럼 **판매자 화면과 고객 화면을 나란히 두고 보는** 경로에
 * 필요하다(S-02 가 그 두 버튼을 새 창으로 여는 것과 같은 이유). 새 탭은 `<a target>` 으로
 * 둔다 — 사용자가 직접 누른 링크라 팝업 차단에 걸리지 않는다.
 */
function ScreenCard({
  screen,
  sid,
  onOpen,
}: {
  screen: Screen;
  sid: string;
  onOpen: (path: string) => void;
}) {
  const needsSession = screen.route.includes(":sid");
  const path = useMemo(
    () => (needsSession ? screen.route.replace(":sid", encodeURIComponent(sid)) : screen.route),
    [needsSession, screen.route, sid],
  );
  const locked = needsSession && sid === "";

  return (
    <article className={`adm__screen ${locked ? "adm__screen--locked" : ""}`}>
      <div className="adm__screen-head">
        <h3 className="adm__screen-name">{screen.name}</h3>
        <span className="adm__badge">{screen.audience}</span>
      </div>

      <p className="adm__screen-note">{screen.note}</p>

      {screen.prereq && (
        <p className="adm__prereq">
          <span className="sr-only">전제: </span>
          {screen.prereq}
        </p>
      )}

      <p className="adm__route">
        <code>{screen.route}</code>
      </p>

      <div className="adm__screen-foot">
        <button
          type="button"
          className="adm__btn adm__btn--sm adm__btn--primary"
          disabled={locked}
          onClick={() => onOpen(path)}
        >
          열기
        </button>
        {locked ? (
          <span className="adm__hint">세션 번호가 필요해요</span>
        ) : (
          <a className="adm__newtab" href={path} target="_blank" rel="noreferrer">
            새 탭
          </a>
        )}
      </div>
    </article>
  );
}
