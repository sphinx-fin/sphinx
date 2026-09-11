/**
 * 감사 기록 (`/audit`) — F-CMN-002 의 UI 몫. 소유: 오준서.
 * 기록·집계·검증은 정세현(`AuditLog`·`evidence/`). **화면은 세지 않는다** — 그리기만 한다.
 *
 * ── 왜 이 화면이 있나 ───────────────────────────────────────────────────────
 *
 * 서버가 네 답을 이미 내고 있는데 **화면이 하나도 없었다.** 그래서 기획서 7-4(역이용
 * 방지)의 실물 근거인 `deniedByRole`(역할별로 몇 번 막혔나)을 **심사에서 보여줄 자리가
 * 없었다.** 역할을 안 만든 것(ADR-001)이 코드에만 있으면 그건 주장이지 증거가 아니다.
 *
 * ── ❗S-08 안에 넣지 않는다 — 권한이 다르다 ─────────────────────────────────
 *
 * ```
 * S-08 오해 지도    aggregate:*      COMPL(org) · MGR(branch)
 * 이 화면           audit:read/verify COMPL(org) 뿐 — MGR 부재
 * ```
 *
 * 뷰 하나로 얹으면 **MGR 에게 상시 403 인 탭**이 생긴다. 그리고 성격도 다르다 — S-08 이
 * 답하는 것은 *"고객이 무엇을 모르는가"* 이고 여기는 *"그 기록을 믿을 수 있는가"* 다.
 * 무결성 검증(`audit:verify`)은 집계가 아예 아니라, 오해 지도 안에 두면 갈래가 섞인다.
 *
 * 화면 번호는 안 받는다 — `/guide`·`/console` 과 같은 자리다(명세 8절 표는 제품 흐름의
 * 화면 목록이다). `SCREENS` 에도 안 넣는다.
 *
 * ── ❗개인을 말하지 않는다 — 계약이 그렇게 만들어져 있다 ─────────────────────
 *
 * `audit-summary` 에 `actorId`·`resource` 가 **없다.** 알파가 개방 모드라 원시 엔트리를
 * 열면 **무인증·공개 상태에서 「누가 무엇을 했는가」가 읽힌다**(결정 7.50). 그래서 이
 * 화면에서 *"이 사람이 세션을 만들고 S-04 에 들어갔다"* 를 그릴 수 없고, 그리려면 고칠
 * 자리는 화면이 아니라 계약이다. S-08 의 *"이 화면은 개인을 말하지 않는다"* 와 같은 규약이다.
 *
 * ── ❗기간은 「접근 집계」 하나에만 걸린다 ───────────────────────────────────
 *
 * 넷 중 `from`·`to` 를 받는 것은 `audit-summary` 뿐이다. P3 계량은 **프로세스 메모리라
 * 누적 하나뿐**이고 기간 파라미터가 아예 없다. 그래서 기간 컨트롤을 **머리에 두지 않고
 * 그 카드 안**에 둔다 — 위에 두면 화면 전체가 그 창으로 좁혀진 것처럼 보이고, 계약 주석이
 * 경고하는 *"받아 놓고 무시하면 화면이 좁힌 줄 알고 그린다"* 가 화면 쪽에서 그대로 난다.
 *
 * P3 카드는 자기 창(`since`)을 스스로 적는다. **그 값 없이 `calls` 를 읽으면 재기동 직후의
 * 낮은 값을 보고 「마스킹이 안 돈다」로 읽는다.**
 *
 * ── 판정 3색은 무결성 한 자리에만 ───────────────────────────────────────────
 *
 * `tokens.css` 규칙 1 이 *"채도 있는 색은 판정에만"* 인데, 이 화면에서 **판정과 같은 종류인
 * 값은 체인 무결성 하나**다(온전한가·끊겼는가). 나머지는 전부 수치라 3색을 얹지 않는다 —
 * S-08 이 집계에 3색을 안 쓰는 이유와 같다(수치에 얹으면 성과 지표로 읽힌다).
 * 조건은 `/console` 과 같다: 제품 흐름 밖 · 라벨 병기(규칙 3) · 고객 비노출(COMPL 전용).
 *
 * ❗**이 예외는 `tokens.css` 규칙 1 옆에 아직 안 적혀 있다** — `/console` 과 같은 상태이고,
 * 그 자리를 메우는 것은 별도 이슈다(`#600` 계열). 두 화면이 같은 근거를 각자 들고 있는
 * 상태라, 옮겨 적을 때 둘을 같이 본다.
 *
 * ── 폴링하지 않는다 ─────────────────────────────────────────────────────────
 *
 * `/console` 과 반대다. 저쪽은 *"지금"* 을 묻고 여기는 **쌓인 기록**을 묻는다 — 5초마다
 * 다시 세도 답이 안 바뀐다. 그리고 `audit-verify` 는 **해시 체인을 재계산**한다: 폴링에
 * 얹으면 감사 화면을 열어 둔 것만으로 서버가 그 일을 계속 한다.
 */
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiRequestError, get } from "../api/client";
import ErrorNote from "../components/ErrorNote";
import { describeError, type ShownError } from "../lib/errorText";
import type { AuditSummary, AuditVerify, PiiSummary } from "../api/types";
import "./Audit.css";

/** 기간 프리셋. 값은 「지금부터 몇 시간 전」이고, `null` 은 전체다. */
const RANGES = [
  { id: "all", label: "전체", hours: null },
  { id: "24h", label: "최근 24시간", hours: 24 },
  { id: "7d", label: "최근 7일", hours: 24 * 7 },
] as const;

type RangeId = (typeof RANGES)[number]["id"];

/** 한 카드가 실패해도 나머지는 그린다 — 셋이 다른 질문이라 같이 눕힐 이유가 없다. */
type Loaded<T> = { value: T; failed: false } | { value: null; failed: true };

function stamp(iso: string | null): string {
  if (iso === null) return "—";
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, "0");
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())} `
    + `${p(t.getHours())}:${p(t.getMinutes())}:${p(t.getSeconds())}`;
}

/** 큰 수는 자릿수가 곧 정보다 — 세 자리마다 끊는다. */
const num = (n: number) => n.toLocaleString("ko-KR");

/**
 * 서버가 실제로 쓴 창을 문장으로.
 *
 * ❗**`null` 을 「—」로 흘리지 않는다.** 계약에서 `null` 은 「값이 없다」가 아니라
 * **「끝이 없다」**(처음부터 · 지금까지)라, 빈 칸으로 그리면 *"서버가 값을 못 줬다"* 로
 * 읽힌다 — 이 화면이 다른 자리에서 지키는 것과 정확히 반대가 된다.
 */
function windowText(from: string | null, to: string | null): string {
  if (from === null && to === null) return "전체 기간 — 처음부터 지금까지";
  if (to === null) return `${stamp(from)} 부터 지금까지`;
  if (from === null) return `처음부터 ${stamp(to)} 까지 (끝은 제외)`;
  return `${stamp(from)} ~ ${stamp(to)} (끝은 제외)`;
}

export default function Audit() {
  const [summary, setSummary] = useState<Loaded<AuditSummary> | null>(null);
  const [verify, setVerify] = useState<Loaded<AuditVerify> | null>(null);
  const [pii, setPii] = useState<Loaded<PiiSummary> | null>(null);
  /** 403 은 오류가 아니라 정상 결과다 — 에러와 따로 들고 있다(S-08·`/console` 과 같은 판단). */
  const [blocked, setBlocked] = useState(false);
  const [error, setError] = useState<ShownError | null>(null);
  const [busy, setBusy] = useState(false);
  const [range, setRange] = useState<RangeId>("all");

  const load = useCallback(async (rangeId: RangeId) => {
    setBusy(true);
    const preset = RANGES.find((r) => r.id === rangeId) ?? RANGES[0];
    const qs = new URLSearchParams();
    if (preset.hours !== null) {
      qs.set("from", new Date(Date.now() - preset.hours * 3600_000).toISOString());
    }
    const suffix = qs.toString() ? `?${qs}` : "";
    try {
      /* ❗**접근 집계를 먼저 혼자 부른다.** 셋을 한꺼번에 던지면 403 하나가 「차단됨」인지
         「그 카드만 실패」인지 가려면 셋의 status 를 다 봐야 한다. 이 화면의 권한은
         `audit:read` 하나로 대표되므로, 그것으로 문을 판정하고 나머지는 카드별로 다룬다. */
      const first = await get<AuditSummary>(`/dashboard/audit-summary${suffix}`);
      setSummary({ value: first, failed: false });
      setBlocked(false);
      setError(null);

      /* 나머지 둘은 **따로 실패시킨다.** `audit-verify` 는 action 이 아예 달라서
         (`audit:verify`) 한쪽만 막히는 조합이 정책상 가능하다 — 그때 화면 전체를 눕히면
         읽을 수 있는 것까지 못 읽는다. */
      const [v, p] = await Promise.all([
        get<AuditVerify>("/dashboard/audit-verify").then(
          (r) => ({ value: r, failed: false }) as Loaded<AuditVerify>,
          () => ({ value: null, failed: true }) as Loaded<AuditVerify>),
        get<PiiSummary>("/dashboard/pii-summary").then(
          (r) => ({ value: r, failed: false }) as Loaded<PiiSummary>,
          () => ({ value: null, failed: true }) as Loaded<PiiSummary>),
      ]);
      setVerify(v);
      setPii(p);
    } catch (e) {
      setSummary(null);
      setVerify(null);
      setPii(null);
      if (e instanceof ApiRequestError && e.status === 403) {
        setBlocked(true);
        setError(null);
      } else {
        setBlocked(false);
        setError(describeError(e));
      }
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => { void load(range); }, [load, range]);

  /* ── 차단됨 — 오류가 아니라 정상 결과다 ─────────────────────────────────── */
  if (blocked) {
    return (
      <main className="au">
        <p><Link className="au__back" to="/">← 세션 시작</Link></p>
        <h1>감사 기록</h1>
        <section className="au__blocked">
          <h2>이 역할은 감사 기록을 볼 수 없어요</h2>
          <p>접근 기록과 무결성 검증은 준법감시만 볼 수 있어요.</p>
          <p className="au__blocked-why">
            <strong>누가 무엇을 봤는지</strong>를 남기는 것과, 그 기록을 읽을 수 있는 것은
            다른 권한이에요. 기록을 만드는 쪽이 그것을 읽을 수 있으면 감사가 성립하지 않아요.
          </p>
          <p><Link className="au__back" to="/">세션 시작 화면으로</Link></p>
        </section>
      </main>
    );
  }

  if (summary === null && error === null) {
    return (
      <main className="au">
        <p><Link className="au__back" to="/">← 세션 시작</Link></p>
        <h1>감사 기록</h1>
        <p className="au__loading">기록을 세고 있어요…</p>
      </main>
    );
  }

  return (
    <main className="au">
      <header className="au__head">
        <div>
          <p><Link className="au__back" to="/">← 세션 시작</Link></p>
          <h1>감사 기록</h1>
          <p className="au__lead">
            누가 무엇을 했는지는 <b>집계로만</b> 봅니다 — 개인 식별자는 기록에서 응답으로
            나오지 않아요. 읽기 전용이에요.
          </p>
        </div>
        <button type="button" className="au__btn" onClick={() => { void load(range); }} disabled={busy}>
          {busy ? "세는 중…" : "다시 세기"}
        </button>
      </header>

      {error && <ErrorNote error={error} className="au__error" title="기록을 못 받았어요. " />}

      {verify && <VerifyCard state={verify} />}
      {summary && (
        <AccessCard state={summary} range={range} onRange={setRange} busy={busy} />
      )}
      {pii && <PiiCard state={pii} />}
    </main>
  );
}

/**
 * 체인 무결성. **이 화면에서 3색을 쓰는 유일한 자리다** — 값이 판정과 같은 종류다.
 *
 * ❗`brokenAt` 의 「없음」은 `-1` 이지 `0` 이 아니다. 0 으로 읽으면 **첫 항목이 끊긴 것**이
 * 되므로 `ok` 로 갈래를 나누고 그 값을 직접 비교하지 않는다.
 */
function VerifyCard({ state }: { state: Loaded<AuditVerify> }) {
  if (state.failed) return <FailedCard title="체인 무결성" action="audit:verify" />;
  const v = state.value;
  return (
    <section className="au__card" data-ok={v.ok} aria-labelledby="au-verify">
      <header className="au__card-head">
        <h2 id="au-verify">체인 무결성</h2>
        {/* 색 + 글자. 색만으로 말하는 자리를 안 만든다(토큰 규칙 3). */}
        <span className="au__chip" data-ok={v.ok}>{v.ok ? "이어져 있음" : "끊겼음"}</span>
      </header>
      <p className="au__big">{num(v.checked)}<span>건 검사</span></p>
      {v.ok ? (
        <p className="au__note">
          {/* ❗`</b>` 를 줄 끝에 두지 않는다 — JSX 가 **태그에 붙은 줄바꿈을 통째로 지워서**
              「자른경우」가 된다(실측). 두 글자 사이의 줄바꿈만 공백 하나로 남는다. */}
          기록이 append-only 해시 체인으로 이어져 있어요.
          중간을 고치거나 <b>꼬리를 자른 경우</b>까지 여기서 드러나요.
        </p>
      ) : (
        <>
          <p className="au__note au__note--bad">{v.reason || "사유가 비어 있어요 — 서버 로그를 봐요."}</p>
          <dl className="au__facts">
            <div><dt>끊긴 자리</dt><dd>{num(v.brokenAt)}번째</dd></div>
            <div><dt>끊긴 seq</dt><dd>{num(v.brokenSeq)}</dd></div>
          </dl>
        </>
      )}
    </section>
  );
}

/** 접근 집계. **기간 컨트롤이 이 카드 안에 있다** — 다른 카드는 이 창에 안 걸린다. */
function AccessCard({ state, range, onRange, busy }: {
  state: Loaded<AuditSummary>; range: RangeId;
  onRange: (r: RangeId) => void; busy: boolean;
}) {
  if (state.failed) return <FailedCard title="접근 집계" action="audit:read" />;
  const s = state.value;
  return (
    <section className="au__card" aria-labelledby="au-access">
      <header className="au__card-head">
        <h2 id="au-access">접근 집계</h2>
        <div className="au__range" role="group" aria-label="기간">
          {RANGES.map((r) => (
            <button key={r.id} type="button" className="au__range-btn"
                    aria-pressed={range === r.id} disabled={busy}
                    onClick={() => onRange(r.id)}>{r.label}</button>
          ))}
        </div>
      </header>

      {/* ❗**서버가 실제로 쓴 창을 그린다.** 화면이 보낸 값을 되풀이하면 서버가 그것을
          무시했을 때 알 방법이 없다 — 계약이 `from`·`to` 를 응답에 되돌려 주는 이유다. */}
      <p className="au__window">{windowText(s.from, s.to)}</p>

      <p className="au__big">{num(s.total)}<span>건</span></p>

      {/* ❗**`unreadable` 을 `total` 에 안 더한다.** 더하면 total 이 「읽을 수 있었던 것」으로
          조용히 좁혀진다(결정 5.40). 0 일 때도 지우지 않는다 — 「0 건」과 「안 셌다」가
          같아지는 자리다. */}
      <p className={s.unreadable > 0 ? "au__unreadable au__unreadable--some" : "au__unreadable"}>
        읽지 못한 기록 {num(s.unreadable)}건 — 위 수에 <b>안 들어가 있어요</b>.
      </p>

      {/* 기획 7-4 의 실물 숫자다. 이 카드에서 제일 먼저 읽혀야 한다. */}
      <h3 className="au__sub">역할별 차단 <em>401 · 403 으로 끝난 접근</em></h3>
      {Object.keys(s.deniedByRole).length > 0 ? (
        <Counts map={s.deniedByRole} />
      ) : (
        <p className="au__empty">이 기간에 차단된 접근이 없어요.</p>
      )}

      <h3 className="au__sub">action 별</h3>
      {Object.keys(s.byAction).length > 0 ? <Counts map={s.byAction} />
        : <p className="au__empty">이 기간에 기록이 없어요.</p>}

      <h3 className="au__sub">결과 코드별</h3>
      {Object.keys(s.byResultCode).length > 0 ? <Counts map={s.byResultCode} />
        : <p className="au__empty">이 기간에 기록이 없어요.</p>}
    </section>
  );
}

/** P3 마스킹 계량. **창(`since`)을 값과 같은 크기로 적는다.** */
function PiiCard({ state }: { state: Loaded<PiiSummary> }) {
  if (state.failed) return <FailedCard title="개인정보 마스킹 계량" action="audit:read" />;
  const p = state.value;
  return (
    <section className="au__card" aria-labelledby="au-pii">
      <header className="au__card-head">
        <h2 id="au-pii">개인정보 마스킹 계량 <em>P3 경계</em></h2>
      </header>

      {/* ❗**이 줄이 없으면 숫자가 거짓말을 한다.** 계량기가 프로세스 메모리라 재기동하면
          0 부터 다시 센다 — 낮은 값을 보고 「마스킹이 안 돈다」로 읽는 자리다(계약 주석). */}
      <p className="au__window">
        {stamp(p.since)} <span>부터 누적</span>
        <em>— 서버가 다시 뜨면 0 부터 다시 세요(기록이 아니라 관측값이에요)</em>
      </p>

      <div className="au__pair">
        <div><p className="au__big">{num(p.calls)}<span>건 통과</span></p>
          <p className="au__cap">고객 텍스트가 ai-service 로 나간 호출 — <b>안 지워진 것도</b> 세요</p></div>
        <div><p className="au__big">{num(p.callsWithRemovals)}<span>건에서 삭제</span></p>
          <p className="au__cap">그중 무언가 실제로 지워진 호출</p></div>
      </div>

      <h3 className="au__sub">종류별 삭제 <em>합계 {num(p.removedTotal)}건</em></h3>
      {/* ❗**0 인 종류를 지우지 않는다** — 키를 빼면 「0 건」과 「그런 패턴이 없다」가 같아진다.
          ❗**키 순서에 안 기댄다** — 계약이 순서를 보장하지 않는다(그 주석). 이름으로 정렬한다. */}
      <Counts map={p.removedByKind} sort="key" />
    </section>
  );
}

/**
 * 이름 → 건수 표.
 *
 * 기본은 **건수 내림차순**이다(큰 것이 먼저 읽혀야 한다). `sort="key"` 는 계약이 순서를
 * 보장하지 않는 맵에 쓴다 — 그때는 이름순이 재현 가능한 유일한 순서다.
 */
function Counts({ map, sort = "count" }: { map: Record<string, number>; sort?: "count" | "key" }) {
  const rows = Object.entries(map).sort(
    sort === "key" ? (a, b) => a[0].localeCompare(b[0]) : (a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const max = Math.max(1, ...rows.map(([, n]) => n));
  return (
    <table className="au__counts">
      <tbody>
        {rows.map(([name, n]) => (
          <tr key={name}>
            <th scope="row">{name}</th>
            {/* 막대는 한 가지 잉크의 길이로만 말한다 — 계열 색을 주면 판정 3색과 경쟁한다. */}
            <td className="au__bar-cell">
              {/* ❗**0 은 막대를 안 그린다.** `min-width` 가 있어서 폭 0%% 도 점으로 남는데,
                  그러면 「0 건」이 「아주 조금」으로 보인다 — 0 을 지우지 않는 이 표의
                  이유(「0 건」과 「그런 패턴이 없다」를 가른다)를 그 점이 도로 깬다. */}
              {n > 0 && <span className="au__bar" style={{ width: `${(n / max) * 100}%` }} />}
            </td>
            <td className="au__count">{num(n)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * 한 카드만 못 받았을 때.
 *
 * 빈칸으로 두지 않는다 — 「볼 게 없다」와 「못 봤다」가 화면에서 같아지면, 정작 그 카드가
 * 말해야 할 때 아무 일도 안 일어난 것처럼 보인다.
 */
function FailedCard({ title, action }: { title: string; action: string }) {
  return (
    <section className="au__card au__card--failed">
      <header className="au__card-head"><h2>{title}</h2></header>
      <p className="au__note au__note--bad">
        이 값을 못 받았어요. 권한(<code>{action}</code>)이 없거나 서버가 답하지 못했어요 —
        나머지 카드는 그대로예요.
      </p>
    </section>
  );
}
