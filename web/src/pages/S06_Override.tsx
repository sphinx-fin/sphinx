/**
 * S-06 적색 승인 (관리자 화면) — F-GTE-002 의 UI 몫. 소유: 오준서.
 * 승인 API·사유 검증·COMPL 통보는 강희진.
 *
 * 한 화면이 두 사람을 받는다. 판매자는 **요청**하고 MGR 은 **승인**한다 — 상태가
 * `overrideStatus` 하나로 갈리므로 화면을 둘로 쪼개지 않았다. 쪼개면 같은 세션을 두 URL 로
 * 보게 되고, 승인자가 "판매자가 뭘 적었는지" 를 다른 화면에서 찾아야 한다.
 *
 * ── 명세가 화면에 건 제약 ────────────────────────────────────────────────────
 *
 * ① **사유 미입력 시 UI 차단** (역할표 §화면 S-06)
 *    API 가 30자 미만을 400 으로 막는다(ADR-002 견제 장치). 화면이 그걸 먼저 막지 않으면
 *    판매자는 다 적고 나서야 거절당한다. 남은 글자 수를 실시간으로 보인다.
 *
 * ② **승인자는 사유를 반드시 본다**
 *    `SessionResponse` 에 `overrideReason` 이 실려 나오는 이유가 이것이다(#68 리뷰).
 *    이게 화면에 안 보이면 승인자가 사유를 못 보고 승인하게 되고, API 에서 30자를 강제한
 *    의미가 화면에서 사라진다. 그래서 승인 버튼 **위에** 사유 전문을 놓는다.
 *
 * ③ **"오버라이드 없음" 은 값으로 읽는다** (#116 · #125)
 *    `SessionResponse` 로는 `overrideStatus: "NONE"` 이 나간다. `!overrideStatus` 로
 *    읽으면 "없다" 와 "안 실렸다" 가 같아진다 — 필드가 빠진 응답을 "요청 없음"으로 잘못
 *    읽고 요청 화면을 띄우게 된다. 값으로 비교한다.
 *
 * ④ **적색이 아니면 요청 자체를 막는다**
 *    계약이 409 OVERRIDE_NOT_ELIGIBLE 로 막는다. 화면도 같은 판단을 먼저 한다 —
 *    녹색 세션에서 오버라이드 UI 가 보이는 것 자체가 이 제품의 논지와 어긋난다.
 *
 * ⑤ **승인 실패를 조용히 넘기지 않는다**
 *    이슈 #124 가 열려 있다 — MGR 에게 `session:interview` 그랜트가 없어서 승인자가 승인
 *    대상을 못 읽는다. 그게 닫히기 전에는 세션 조회가 403 으로 떨어질 수 있고, 그때
 *    화면이 빈 상태로 있으면 원인을 알 수 없다. 403 을 따로 문구로 낸다.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, get, post } from "../api/client";
import type { GatePreview, OverrideResponse, SessionResponse } from "../api/types";
import ErrorNote from "../components/ErrorNote";
import { describeError, type ShownError } from "../lib/errorText";
import "./S06_Override.css";

/** ADR-002 견제 장치. 서버가 400 으로 막는 값과 같아야 한다 — 화면이 더 느슨하면 의미가 없다. */
const MIN_REASON = 30;

export default function S06Override() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();

  const [session, setSession] = useState<SessionResponse | null>(null);
  const [gate, setGate] = useState<GatePreview | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ShownError | null>(null);
  /** #124 가 닫히기 전까지 승인자가 만날 수 있는 상태. 빈 화면으로 두지 않는다(설계 판단 ⑤). */
  const [forbidden, setForbidden] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const s = await get<SessionResponse>(`/sessions/${sid}`);
      setSession(s);
      setForbidden(false);
      try {
        // `/gate-preview` 는 GateResult 가 아니라 GatePreview 다 — S-05 와 같은 자리다(#132 리뷰).
        // 이 화면이 읽는 것은 signal 뿐이지만, 타입을 좁게 받으면 남는 필드를 TS 가 잡아주지
        // 않아 나중에 recorded·suitabilityStatus 를 쓸 때 조용히 undefined 가 된다.
        setGate(await get<GatePreview>(`/sessions/${sid}/gate-preview`));
      } catch {
        // 신호를 못 읽어도 오버라이드 상태는 보여준다 — 승인자에게는 사유가 먼저다.
        setGate(null);
      }
      setError(null);
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 403) setForbidden(true);
      else setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, [sid]);

  useEffect(() => { void load(); }, [load]);

  async function request() {
    setBusy(true);
    try {
      await post<OverrideResponse>(`/sessions/${sid}/override`, { reason: reason.trim() });
      await load();
      setReason("");
      setError(null);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  async function approve() {
    setBusy(true);
    try {
      // 본문이 없다 — 사유는 요청 시 이미 기록됐고 승인자는 인증 주체에서 얻는다(계약).
      await post<OverrideResponse>(`/sessions/${sid}/override/approve`, {});
      await load();
      setError(null);
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <main className="s06"><p className="s06__loading">세션을 불러오는 중입니다…</p></main>;
  }

  if (forbidden) {
    return (
      <main className="s06">
        <h1>적색 승인</h1>
        <p className="s06__error" role="alert">
          이 세션을 열 권한이 없습니다. 승인자 계정에 세션 조회 권한이 없는 상태로 보입니다
          (이슈 #124). 권한이 붙기 전까지는 승인할 수 없습니다.
        </p>
      </main>
    );
  }

  // 값으로 읽는다 — 부재로 두면 "없다" 와 "안 실렸다" 가 같아진다(설계 판단 ③).
  const status = session?.overrideStatus ?? "NONE";
  const isRed = gate?.signal === "RED";
  const trimmed = reason.trim();
  const remaining = MIN_REASON - trimmed.length;
  const canRequest = remaining <= 0 && !busy;

  return (
    <main className="s06">
      <header className="s06__head">
        <h1>적색 승인</h1>
        <p className="s06__sid">
          세션 <code>{sid}</code>
          {session?.contractRef && <> · 계약건 <code>{session.contractRef}</code></>}
        </p>
      </header>

      {error && <ErrorNote error={error} className="s06__error" />}

      {/* ── 상태 ──────────────────────────────────────────────────────────── */}
      <section className={`s06__state s06__state--${status.toLowerCase().replace(/_/g, "-")}`}>
        {status === "NONE" && <p><strong>오버라이드 요청 없음</strong></p>}
        {status === "PENDING_APPROVAL" && <p><strong>승인 대기</strong> — 판매자가 적색 진행을 요청했습니다.</p>}
        {status === "APPROVED" && (
          <p>
            <strong>승인됨</strong>
            {session?.overrideApprover && <> · 승인자 {session.overrideApprover}</>}
            {session?.overrideDecidedAt && <> · {new Date(session.overrideDecidedAt).toLocaleString("ko-KR")}</>}
          </p>
        )}
      </section>

      {/* ── 판매자: 요청 ──────────────────────────────────────────────────── */}
      {status === "NONE" && (
        isRed ? (
          <section className="s06__request">
            <h2>적색 진행 요청</h2>
            <p className="s06__warn">
              적색은 고객이 상품을 이해하지 못했다는 판정입니다. 그럼에도 진행하려는 사유를
              적어 주세요. <strong>이 사유는 기록으로 남고 준법감시(COMPL)에 통보됩니다.</strong>
            </p>
            <label className="s06__label" htmlFor="reason">사유</label>
            <textarea
              id="reason"
              className="s06__textarea"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={5}
              disabled={busy}
              placeholder="어떤 항목을 어떻게 보완했는지, 왜 진행이 타당한지 적어 주세요."
            />
            {/* 설계 판단 ① — 다 적고 나서 거절당하지 않게 미리 막는다 */}
            <p className={`s06__counter ${remaining > 0 ? "s06__counter--short" : ""}`}>
              {remaining > 0
                ? `${remaining}자 더 필요합니다 (최소 ${MIN_REASON}자)`
                : `${trimmed.length}자 — 요청할 수 있습니다`}
            </p>
            <button type="button" className="s06__btn s06__btn--primary" onClick={request} disabled={!canRequest}>
              {busy ? "요청 중…" : "승인 요청"}
            </button>
          </section>
        ) : (
          /* 설계 판단 ④ — 적색이 아니면 요청 UI 자체를 안 보인다 */
          <section className="s06__ineligible">
            <p>
              적색 판정 세션이 아닙니다. 오버라이드는 적색에서만 요청할 수 있습니다.
              {gate && <> 현재 신호: <strong>{gate.signal}</strong></>}
            </p>
          </section>
        )
      )}

      {/* ── MGR: 승인 ─────────────────────────────────────────────────────── */}
      {status === "PENDING_APPROVAL" && (
        <section className="s06__approve">
          <h2>승인 검토</h2>
          {/* 설계 판단 ② — 승인 버튼 위에 사유 전문을 놓는다 */}
          <div className="s06__reason-shown">
            <span className="s06__label">판매자가 적은 사유</span>
            <blockquote>{session?.overrideReason ?? "(사유를 읽지 못했습니다)"}</blockquote>
          </div>
          <p className="s06__warn">
            승인하면 <strong>불변 기록에 남고 준법감시에 자동 통보</strong>됩니다. 반려
            기능은 현재 없습니다 — 승인하지 않으면 세션은 그대로 보류(적색)입니다.
          </p>
          <button type="button" className="s06__btn s06__btn--primary" onClick={approve} disabled={busy}>
            {busy ? "승인 중…" : "승인합니다"}
          </button>
        </section>
      )}

      <footer className="s06__actions">
        <button type="button" className="s06__btn" onClick={() => navigate(`/judgment/${sid}`)}>
          판정 결과로
        </button>
        <button type="button" className="s06__btn s06__btn--quiet" onClick={() => void load()}>
          새로고침
        </button>
      </footer>
    </main>
  );
}

