/**
 * S-07 이해 기록 리포트 (판매자 화면) — F-GTE-004 의 UI 몫. 소유: 오준서.
 * 리포트 조립·해시 체인·PDF 는 정세현(`evidence/ReportService`).
 *
 * ── 명세가 화면에 건 제약 ────────────────────────────────────────────────────
 *
 * ① **발행은 화면이 자동으로 하지 않는다** (계약 POST/GET 분리 근거)
 *    계약이 발행을 POST 로 뗀 이유가 감사다 — GET 이 발행까지 하면 감사 로그에서
 *    *"읽었다"* 와 *"발행했다"* 가 구별되지 않고, MGR·COMPL 이 남의 세션을 열람하는 것만으로
 *    발행 기록이 생긴다. 그 근거는 화면에도 그대로 걸린다: **적재는 GET 만 하고, POST 는
 *    사람이 누를 때만 나간다.** 없으면 자동으로 발행해 주는 편의를 넣는 순간, 계약이
 *    메서드를 가른 의미가 화면 층에서 사라진다.
 *
 * ② **404 는 오류가 아니라 상태다** (계약 GET 404 설명)
 *    *"세션이 없거나 아직 발행된 리포트가 없다. 후자는 오류가 아니라 상태다 — 화면은
 *    '발행하기' 를 띄우면 된다."* 그래서 `NOT_FOUND` 를 빨간 오류로 그리지 않고 발행
 *    안내로 그린다. 세션 자체가 없는 경우와는 문구로 가른다.
 *
 * ③ **`previewUrl`·`downloadUrl` 이 null 이면 링크를 그리지 않는다**
 *    계약이 이 둘을 nullable 로 둔 이유를 그대로 적어뒀다 — *"값을 채우면 계약이 '이 URL 로
 *    가면 문서가 있다' 를 보장하는데 404 가 난다. 스키마 검증은 통과하고 화면은 링크를
 *    그리며, 눌러야 드러난다."* ❗**지금은 값이 온다**(이슈 #233 으로 PDF 가 붙었고 서버가
 *    분기 없이 채운다) — 이 문단이 *"아직 없으므로 항상 null"* 이라고 적어 두고 있었는데
 *    그건 낡은 문면이었다(이슈 #417). 방어는 그대로 둔다: 못 채우는 상태가 생기면 그때
 *    화면이 알아서 링크를 뺀다. 비활성 버튼도 두지 않는다. 없는 것은 없다고 쓴다.
 *
 * ④ **`contentHash` 를 자르지 않는다**
 *    고객이 받은 문서를 나중에 대조하는 값이다. 앞 8자만 보이면 대조가 성립하지 않는다.
 *    64자 전문을 그대로 두고 복사할 수 있게 한다 — 이 화면에서 제일 중요한 한 줄이다.
 *
 * ⑤ **재발행의 뜻을 정확히 쓴다** (계약 멱등 설명 · ADR-004)
 *    내용이 그대로면 다시 발행하지 않고 기존 것을 돌려준다. 내용이 달라지면 새로 발행하되
 *    **이전 발행 기록은 지우지 않는다** — *"교부 시점에 무엇이 적혀 있었는가"* 에 답하려면
 *    둘이 나란히 남아야 한다. 버튼 문구가 "덮어쓴다" 로 읽히면 안 되는 이유다.
 *
 * ⑥ **`report:issue` 는 SELLER own_session 뿐이다** (#95)
 *    MGR·COMPL 은 감독을 위해 남의 세션을 *읽는* 역할이고 교부는 그 세션을 진행한 창구
 *    직원이 한다. 그래서 조회는 되는데 발행만 403 인 상태가 정상적으로 존재한다 —
 *    그때 화면이 그냥 실패로 보이면 승인자가 원인을 모른다. 403 을 따로 문구로 낸다.
 *
 * ⑦ **`previewUrl`·`downloadUrl` 에는 API 접두어를 화면이 붙인다** (이슈 #254)
 *    이 둘은 **API 기준 경로**(`/sessions/…`)라 그대로 `href` 에 넣으면 안 된다.
 *    `<a href>` 는 `client.ts` 를 안 타므로 `BASE` 가 안 붙고, 브라우저는 web 오리진을
 *    친다 — 거기서 `/sessions/…` 는 `app.conf` 의 `try_files … /index.html` 로 떨어져
 *    **404 가 아니라 200 index.html** 이 온다. 미리보기는 하얀 새 탭, 내려받기는 PDF 대신
 *    HTML 파일이고 **오류는 하나도 안 난다.** ③이 *"없는 것은 없다고 쓴다"* 로 막은 것과
 *    같은 종류의 조용한 실패인데, 이쪽은 값이 **있어서** 나는 것이다.
 *    서버가 접두어를 지어 넣지 않는 이유는 `client.ts` 의 `BASE` javadoc 에 적어 뒀다.
 *
 * ── 지금 서버는 목이다 ───────────────────────────────────────────────────────
 *
 * `SessionController.reportPayload()` 가 `TODO(정세현)` 목이고 이슈 #54 가 배선을 들고 있다.
 * 목은 발행 여부를 몰라서 **GET 이 404 를 내지 않고 늘 리포트를 돌려준다.** 그래도 ②의
 * 404 경로를 지금 구현해 둔다 — 목 동작에 맞춰 지으면 배선이 붙는 날 화면이 "발행 안 됨"
 * 상태를 처음 만나고, 그때는 데모 주간이다.
 */
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiRequestError, BASE, get, post } from "../api/client";
import type { ReportResponse } from "../api/types";
import ErrorNote from "../components/ErrorNote";
import { describeError, type ShownError } from "../lib/errorText";
import "./S07_Report.css";

/** 적재 결과. "아직 발행 안 됨" 은 실패가 아니라 상태이므로 에러와 따로 둔다(설계 판단 ②). */
type Loaded =
  | { kind: "issued"; report: ReportResponse }
  | { kind: "not-issued" }
  | { kind: "no-session" };

export default function S07Report() {
  const { sid = "" } = useParams();
  const navigate = useNavigate();

  const [state, setState] = useState<Loaded | null>(null);
  const [loading, setLoading] = useState(true);
  const [issuing, setIssuing] = useState(false);
  /**
   * 적재·발행 공통 오류 문구. 배너가 한 곳이라 상태도 하나로 둔다 — 둘로 나눠 놓고
   * 같은 자리에 그리면 이름만 갈리고 화면은 같아서, 나중에 어느 쪽인지 알 수 없다.
   */
  const [error, setError] = useState<ShownError | null>(null);
  const [copied, setCopied] = useState(false);

  /* ── 적재 — GET 만 한다. 발행하지 않는다(설계 판단 ①) ────────────────────── */
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const r = await get<ReportResponse>(`/sessions/${sid}/report`);
      setState({ kind: "issued", report: r });
    } catch (e) {
      // 404 는 두 가지를 함께 뜻한다(계약). 세션이 없는 것과 아직 발행하지 않은 것은
      // 다음 행동이 다르므로 — 전자는 되돌아가야 하고 후자는 발행하면 된다 — 세션을
      // 한 번 더 확인해서 가른다.
      if (e instanceof ApiRequestError && e.status === 404) {
        setState(await sessionExists(sid) ? { kind: "not-issued" } : { kind: "no-session" });
      } else {
        setState(null);
        setError(describeError(e));
      }
    } finally {
      setLoading(false);
    }
  }, [sid]);

  useEffect(() => { void load(); }, [load]);

  /* ── 발행 — 사람이 누를 때만(설계 판단 ①) ────────────────────────────────── */
  async function issue() {
    setIssuing(true);
    setError(null);
    try {
      const r = await post<ReportResponse>(`/sessions/${sid}/report`, {});
      setState({ kind: "issued", report: r });
    } catch (e) {
      // 조회는 되는데 발행만 막히는 상태가 정상적으로 있다(설계 판단 ⑥).
      if (e instanceof ApiRequestError && e.status === 403) {
        // 공용 FORBIDDEN 문면보다 이 자리가 구체적이다 — 누가 교부할 수 있는지까지
        // 말해야 판매자가 다음 행동을 안다. 원문은 공용 헬퍼와 같은 모양으로 남긴다.
        setError({
          text:
            "리포트를 교부할 권한이 없어요. 교부는 세션을 진행한 창구 직원만 할 수 있어요.",
          detail: `${e.code}: ${e.message}`,
        });
      } else {
        setError(describeError(e));
      }
    } finally {
      setIssuing(false);
    }
  }

  async function copyHash(hash: string) {
    try {
      await navigator.clipboard.writeText(hash);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // 클립보드가 막힌 환경(비 HTTPS 등)에서도 해시 자체는 화면에 다 보인다.
      setCopied(false);
    }
  }

  if (loading) {
    return <main className="s07"><p className="s07__loading">리포트를 불러오고 있어요…</p></main>;
  }

  return (
    <main className="s07">
      <header className="s07__head">
        <h1>이해 기록 리포트</h1>
        <p className="s07__sid">세션 <code>{sid}</code></p>
      </header>

      {error && <ErrorNote error={error} className="s07__error" />}

      {state?.kind === "no-session" && (
        <section className="s07__empty">
          <h2>세션을 찾을 수 없어요</h2>
          <p>주소의 세션 번호를 확인해 주세요.</p>
        </section>
      )}

      {/* ── 아직 발행 전. 오류가 아니라 상태다(설계 판단 ②) ───────────────── */}
      {state?.kind === "not-issued" && (
        <section className="s07__empty">
          <h2>아직 교부하지 않았어요</h2>
          <p>
            이 세션의 이해 기록 리포트가 아직 발행되지 않았습니다. 발행하면 판정·재설명 이력이
            기록에서 조립되고, <strong>발행 사실이 감사 기록에 남아요.</strong>
          </p>
          <button type="button" className="s07__btn s07__btn--primary" onClick={issue} disabled={issuing}>
            {issuing ? "발행 중…" : "리포트 발행"}
          </button>
        </section>
      )}

      {state?.kind === "issued" && (
        <>
          <section className="s07__meta">
            <dl>
              <div>
                <dt>리포트 번호</dt>
                <dd><code>{state.report.reportId}</code></dd>
              </div>
              <div>
                <dt>발행 시각</dt>
                <dd>{formatAt(state.report.generatedAt)}</dd>
              </div>
            </dl>
          </section>

          {/* ── 내용 해시. 이 화면에서 제일 중요한 한 줄이다(설계 판단 ④) ──── */}
          <section className="s07__hash">
            <h2>내용 해시 <span className="s07__hash-algo">SHA-256</span></h2>
            <p className="s07__hash-note">
              이 문서 <strong>내용</strong>의 해시입니다. 교부받은 문서가 이 기록에서 나온 것인지
              나중에 대조할 수 있습니다. 발행 시각은 내용에 들어가지 않으므로,
              같은 내용을 다시 발행해도 이 값은 같습니다.
            </p>
            <div className="s07__hash-row">
              <code className="s07__hash-value">{state.report.contentHash}</code>
              <button
                type="button"
                className="s07__btn s07__btn--quiet"
                onClick={() => void copyHash(state.report.contentHash)}
              >
                {copied ? "복사됨" : "복사"}
              </button>
            </div>
          </section>

          {/* ── PDF. 없으면 링크를 그리지 않는다(설계 판단 ③) ──────────────── */}
          <section className="s07__pdf">
            <h2>문서</h2>
            {state.report.previewUrl || state.report.downloadUrl ? (
              /* 접두어는 화면이 붙인다(설계 판단 ⑦) — `<a href>` 는 client.ts 를 안 탄다. */
              <div className="s07__pdf-links">
                {state.report.previewUrl && (
                  <a
                    className="s07__btn"
                    href={`${BASE}${state.report.previewUrl}`}
                    target="_blank"
                    rel="noreferrer"
                  >
                    미리보기
                  </a>
                )}
                {state.report.downloadUrl && (
                  <a className="s07__btn" href={`${BASE}${state.report.downloadUrl}`}>
                    내려받기
                  </a>
                )}
              </div>
            ) : (
              <p className="s07__pdf-none">
                PDF 미리보기는 아직 준비되지 않았습니다. 위 리포트 번호와 내용 해시는 이미
                유효한 기록이며, 문서 파일이 붙으면 이 자리에 표시됩니다.
              </p>
            )}
          </section>

          <footer className="s07__actions">
            {/* 설계 판단 ⑤ — "덮어쓴다"로 읽히면 안 된다. */}
            <button type="button" className="s07__btn" onClick={issue} disabled={issuing}>
              {issuing ? "확인 중…" : "다시 발행"}
            </button>
            <p className="s07__reissue-note">
              내용이 그대로면 새로 발행하지 않고 지금 문서를 그대로 돌려줍니다. 내용이 달라졌다면
              새로 발행되며, <strong>이전 발행 기록은 지워지지 않아요</strong> — 교부 시점에
              무엇이 적혀 있었는지에 답하려면 둘이 나란히 남아야 합니다.
            </p>
          </footer>
        </>
      )}

      <nav className="s07__nav">
        <button type="button" className="s07__btn s07__btn--quiet" onClick={() => navigate(`/judgment/${sid}`)}>
          판정 결과로
        </button>
        <button type="button" className="s07__btn s07__btn--quiet" onClick={() => void load()}>
          새로고침
        </button>
        {/* 피드백 5번 — S-08 에 닿는 길이 S-02 경유 하나뿐이라 리포트에서도 열어 준다. 1차 행동(발행)이 아니라 quiet. */}
        <button type="button" className="s07__btn s07__btn--quiet" onClick={() => navigate("/dashboard")}>
          오해 지도 대시보드
        </button>
      </nav>
    </main>
  );
}

/**
 * 세션이 실재하는지만 본다. `/report` 404 가 "세션 없음"과 "미발행"을 함께 뜻하므로
 * (계약 GET 404 설명) 다음 행동을 가르려면 한 번 더 물어야 한다.
 */
async function sessionExists(sid: string): Promise<boolean> {
  try {
    await get<unknown>(`/sessions/${sid}`);
    return true;
  } catch {
    // 403(권한 밖)도 여기로 온다. 그때 "세션 없음"으로 그리는 편이 낫다 —
    // 존재 여부를 알려주지 않는 쪽이 범위 밖 세션에 대해 안전하다.
    return false;
  }
}

/** ISO 8601 → 사람이 읽는 시각. 실패하면 원문을 그대로 둔다(값을 지어내지 않는다). */
function formatAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "long", timeStyle: "medium",
  }).format(d);
}

