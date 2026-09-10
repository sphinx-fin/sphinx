/**
 * 운영 콘솔 (`/console`) — F-OPS-001 의 화면 몫. 소유: 오준서.
 * 실측은 server(`OpsStatusService`, 강희진). **화면은 재지 않는다** — 그리기만 한다.
 *
 * ── 화면 번호를 안 받는다 (이슈 #522 질문 3 · 정세현·윤지석 합의) ────────────
 *
 * 이슈 본문이 「S-09」로 부르지만 **번호를 주지 않기로 정리됐다.** 명세 8절 표는 *제품
 * 흐름의* 화면 목록이고(S-01~S-08 은 고객·판매자가 계약 직전까지 지나는 경로다) 이 화면은
 * 그 밖이다. 번호를 얹으면 그 구별이 사라진다. `Guide.tsx` 와 같은 자리라 파일 이름도 같은
 * 모양이다 — `S0n_` 없이 이름만. 명세 쪽 「제품 흐름 밖 화면」 절은 정세현 몫이다.
 *
 * `SCREENS` 배열에도 안 넣는다(`App.tsx` 의 `/guide` 주석과 같은 이유).
 *
 * ── 이 화면이 답하는 질문은 하나다: 「지금 무엇이 안 되는가」 ─────────────────
 *
 * ai-service 는 셋으로 실패하는데 **겉모습이 전부 같았다** — 안 떠 있다 · 떴는데 LLM 키가
 * 없다 · 떴는데 공유 시크릿이 어긋나 401. 셋 다 판정이 `AI_SERVICE_UNAVAILABLE` 502 로
 * 떨어지므로 운영자는 **ai-service 를 재시작한다.** 뒤 둘에서 그건 아무것도 안 고친다.
 *
 * ❗**그래서 `DEGRADED` 가 이 화면의 요점이다.** UP/DOWN 둘로만 그리면 「떠 있는데 못 하는
 * 상태」가 **전부 정상으로 보인다.** 카드 색이 노랑인 것 자체가 정보다.
 *
 * ── 판정 3색을 쓴다 (이슈 #522 질문 1 — 팔레트는 내 영역이라 내가 정한다) ────
 *
 * `tokens.css` 규칙 1 이 *"채도 있는 색은 판정에만"* 인데 **여기서는 예외를 둔다.** 근거는
 * 새 색 체계를 만드는 쪽이 더 나쁘다는 것이다 — 화면마다 다른 상태색이 생기면 읽는 사람이
 * 색 체계를 두 벌 배워야 하고, 그러면 규칙 1 이 지키려던 것이 오히려 옅어진다. 조건 셋을
 * 못 박았고(제품 흐름 밖 · 라벨 병기 · 고객 비노출) 그 문면은 `tokens.css` 규칙 1 옆에 있다.
 * 팀 셋 다 「쓰는 데 찬성」이었다(#522 스레드).
 *
 * **라벨을 반드시 병기한다**(규칙 3). 상태 칩은 색 + 글자 둘 다이고, 색만으로 말하는 자리는
 * 이 화면에 없다.
 *
 * ── 폴링: 5초 · 멈출 수 있다 ────────────────────────────────────────────────
 *
 * 매 호출이 서버의 **실측**이다(계약이 캐시를 금지한다). 그래서 화면도 값을 오래 들고
 * 있으면 안 된다 — **요청이 실패하면 직전 카드를 지운다.** 낡은 초록을 남겨 두는 것이 이
 * 화면에서 제일 나쁜 실패다: 「안 되는 것을 보러 온 사람」에게 「다 된다」를 보여준다.
 *
 * 멈춤은 **읽을 시간을 위한 것**이다. 값이 5초마다 갈리면 긴 note 를 다 못 읽는다. 멈춘
 * 동안에는 그 사실과 기준 시각을 화면이 말한다.
 *
 * ── 지연 추이는 이 화면을 연 뒤로만 (브라우저 메모리) ───────────────────────
 *
 * 서버에 시계열을 쌓자는 얘기가 아니다(이슈 #522 범위 밖). 탭을 닫으면 사라지고, 그래도
 * 쓸모가 있다 — *"방금 느려졌는가"* 는 한 번의 숫자로는 못 답한다.
 *
 * ❗**못 잰 자리(`latencyMs: null`)를 0 으로 접지 않는다.** 접으면 「즉시 응답」과 「안
 * 쟀다」가 같은 막대가 된다 — 계약이 그 둘을 가르려고 nullable 로 온 것이라, 화면이 다시
 * 뭉치면 그 뜻이 사라진다. 빈 자리로 그린다.
 *
 * ── 403 은 오류가 아니라 「차단됨」이다 ──────────────────────────────────────
 *
 * `ops:status:read` 는 ADMIN 뿐이다. 계약이 403 을 *"화면이 「차단됨」을 그려야 하므로
 * 선언한다"* 고 적었고, S-08 이 같은 모양으로 그리고 있다 — 빨간 에러 배너가 아니라 설명
 * 화면이다. *"링크가 있다 ≠ 그 API 를 부를 권한이 있다"*.
 */
import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ApiRequestError, get } from "../api/client";
import ErrorNote from "../components/ErrorNote";
import { describeError, type ShownError } from "../lib/errorText";
import type { OpsComponent, OpsHealth, OpsStatus } from "../api/types";
import "./Console.css";

/** 폴링 간격. 이슈 #522 가 정한 값이다 — 실측이라 서버가 매번 상류를 친다. */
const POLL_MS = 5000;

/** 추이로 들고 있는 표본 수. 5초 × 60 = 최근 5분. */
const TREND_SAMPLES = 60;

/**
 * 상태 칩의 글자. **색과 같은 것을 말한다**(토큰 규칙 3 — 색만으로 구분하지 않는다).
 *
 * `DEGRADED` 의 문면이 길지만 줄이지 않았다. 「제한」·「주의」로 줄이면 이 화면이 존재하는
 * 이유가 칩에서 사라진다 — 이 상태의 뜻은 **떠 있다는 것과 못 한다는 것이 동시에 참**이고,
 * 그 둘을 같이 말하지 않으면 운영자가 또 재시작하러 간다.
 */
const HEALTH_LABEL: Record<OpsHealth, string> = {
  UP: "정상",
  DEGRADED: "떠 있는데 못 함",
  DOWN: "안 됨",
};

const pad = (n: number) => String(n).padStart(2, "0");

/**
 * 시:분:초. 실측 시각은 날짜가 아니라 **얼마나 최근인가**를 말하는 값이라 시각만 낸다.
 *
 * ❗**`toLocaleTimeString` 을 안 쓴다.** `ko-KR` 은 「13시 27분 36초」로 낸다 — 5초마다
 * 갈리는 자리에 글자가 아홉 자 더 붙고, 자릿수가 안 맞아 숫자가 좌우로 흔들린다.
 * 로케일마다 모양이 갈리는 것도 이 값에는 손해다(알파 박스와 내 브라우저가 달라진다).
 */
function clock(iso: string): string {
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return iso;
  return `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`;
}

/** 기동 시각은 날짜까지. 「어제부터 떠 있다」가 이 값에서 나온다. */
function stamp(iso: string): string {
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return iso;
  return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} `
    + `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`;
}

/** 초 → 「3일 4시간」. 두 단위까지만 — 운영자가 보는 것은 자릿수이지 초가 아니다. */
function uptime(sec: number): string {
  const d = Math.floor(sec / 86400);
  const h = Math.floor((sec % 86400) / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (d > 0) return `${d}일 ${h}시간`;
  if (h > 0) return `${h}시간 ${m}분`;
  if (m > 0) return `${m}분`;
  return `${sec}초`;
}

export default function Console() {
  const [status, setStatus] = useState<OpsStatus | null>(null);
  const [error, setError] = useState<ShownError | null>(null);
  /** 403 은 오류가 아니라 정상 결과다 — 에러와 따로 들고 있다(S-08 과 같은 판단). */
  const [blocked, setBlocked] = useState(false);
  const [paused, setPaused] = useState(false);
  const [busy, setBusy] = useState(false);
  /** 구성요소별 왕복 시간 표본. **이 화면을 연 뒤로만** 쌓이고 탭을 닫으면 사라진다. */
  const [trend, setTrend] = useState<Record<string, (number | null)[]>>({});

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const next = await get<OpsStatus>("/ops/status");
      setStatus(next);
      setBlocked(false);
      setError(null);
      setTrend((prev) => {
        const out: Record<string, (number | null)[]> = {};
        for (const c of next.components) {
          out[c.id] = [...(prev[c.id] ?? []), c.latencyMs].slice(-TREND_SAMPLES);
        }
        return out;
      });
    } catch (e) {
      /* ❗**직전 값을 남기지 않는다.** 이 화면은 「지금」을 답하는 자리고, 실패한 순간에
         남은 카드는 전부 낡은 값이다 — 그중 초록이 하나라도 있으면 화면이 서버가 하지
         않은 말을 한다. `/ops/status` 가 안 오는 것 자체가 답이기도 하다(서버가 죽었다). */
      setStatus(null);
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

  useEffect(() => { void load(); }, [load]);

  /* 멈춤은 타이머를 **안 거는 것**으로 구현한다. 세워 두고 안에서 건너뛰면 「멈췄다」와
     「도는데 값이 같다」가 개발자 도구에서도 구분이 안 된다. */
  useEffect(() => {
    if (paused) return;
    const id = window.setInterval(() => { void load(); }, POLL_MS);
    return () => window.clearInterval(id);
  }, [paused, load]);

  /* ── 차단됨 — 오류가 아니라 정상 결과다 ─────────────────────────────────── */
  if (blocked) {
    return (
      <main className="cn">
        <p><Link className="cn__back" to="/">← 세션 시작</Link></p>
        <h1>운영 콘솔</h1>
        <section className="cn__blocked">
          <h2>이 역할은 운영 상태를 볼 수 없어요</h2>
          <p>구성요소 상태는 관리자만 볼 수 있어요.</p>
          <p className="cn__blocked-why">
            <strong>지금 채점이 죽어 있다</strong>를 아는 것은 게이트가 느슨해지는 시점을
            고를 수 있다는 뜻이에요. 그래서 판매·관리 역할에는 이 화면을 열어 두지 않아요.
          </p>
          <p><Link className="cn__back" to="/">세션 시작 화면으로</Link></p>
        </section>
      </main>
    );
  }

  if (status === null && error === null) {
    return (
      <main className="cn">
        <p><Link className="cn__back" to="/">← 세션 시작</Link></p>
        <h1>운영 콘솔</h1>
        <p className="cn__loading">상태를 재고 있어요…</p>
      </main>
    );
  }

  const deployment = status?.deployment;
  /* 스택이 빈 문자열인 것은 **로컬**이다(계약: "로컬은 빈 문자열 — 색이 없는 것이 정상").
     그걸 「모른다」로 그리면 로컬이 상시 경고가 된다. */
  const local = deployment !== undefined && deployment.stack === "";

  return (
    <main className="cn">
      <header className="cn__head">
        <div>
          <p><Link className="cn__back" to="/">← 세션 시작</Link></p>
          <h1>운영 콘솔</h1>
          <p className="cn__lead">지금 무엇이 안 되는지를 봅니다. 읽기 전용이에요.</p>
        </div>

        <div className="cn__poll">
          <p className="cn__checked">
            {status ? <>실측 {clock(status.checkedAt)}</> : "값 없음"}
            {busy && <span className="cn__dot" aria-hidden="true" />}
          </p>
          <p className="cn__poll-note">
            {paused ? "멈춰 있어요 — 위 시각 기준이에요." : `${POLL_MS / 1000}초마다 다시 재요.`}
          </p>
          <div className="cn__poll-btns">
            <button type="button" className="cn__btn" aria-pressed={paused}
                    onClick={() => setPaused((p) => !p)}>
              {paused ? "다시 재기 시작" : "잠시 멈춤"}
            </button>
            {/* 멈춘 동안에도 한 번은 재고 싶다 — 긴 note 를 읽고 고친 뒤 확인하는 자리다. */}
            {paused && (
              <button type="button" className="cn__btn" onClick={() => { void load(); }}>
                지금 한 번
              </button>
            )}
          </div>
        </div>
      </header>

      {deployment && (
        <section className="cn__deploy" aria-label="배포">
          <Item label="프로파일" value={deployment.profile || "default"} />
          {/* ❗**blue/green 에 색을 안 준다.** 그 이름은 배포 슬롯이지 상태가 아닌데, 여기
              초록 점을 두면 「green 스택 = 정상」으로 읽힌다 — green 이 아픈 채로 라이브인
              것이 정확히 이 화면이 보여줘야 하는 상황이다. 팔레트 쪽 근거도 같은 방향이다:
              시맨틱 토큰에 「배포 슬롯 색」이 없고, 그걸 만들려면 원시 안료를 화면에서 직접
              불러야 한다(`tokens.css` — 화면은 시맨틱만 부른다). 이름을 글자로 적는다. */}
          <Item label="스택" value={deployment.stack || "— (로컬)"} />
          <Item label="기동" value={stamp(deployment.startedAt)} />
          <Item label="가동" value={uptime(deployment.uptimeSec)} />
        </section>
      )}

      {local && (
        /* ❗**로컬에서만 붙인다.** 두 기동 시각(server 의 devtools 재시작 · ai-service 의
           `uvicorn --reload`)이 로컬에서는 컨테이너가 아니라 워커·빈 생성 시각이라, 나란히
           놓고 「어긋났다」로 읽으면 **양쪽 다 오진**이다(#522 리뷰, 윤지석·강희진 실측).
           배포에는 devtools 도 `--reload` 도 없어 이 조건이 안 붙는다. */
        <p className="cn__caveat">
          로컬이에요. 기동 시각 둘(API 서버 · AI 서비스)은 여기서 <b>컨테이너가 아니라
          워커·빈이 다시 뜬 시각</b>이라, 두 값이 어긋나 보여도 세대 차이가 아니에요.
        </p>
      )}

      {error && <ErrorNote error={error} className="cn__error" title="상태를 못 받았어요. " />}

      {status && (
        <section className="cn__cards">
          {/* 순서를 화면이 다시 정하지 않는다 — 서버가 보낸 순서가 곧 중요도다(계약). */}
          {status.components.map((c) => (
            <Card key={c.id} component={c} samples={trend[c.id] ?? []} />
          ))}
        </section>
      )}
    </main>
  );
}

/** 배포 줄의 한 칸. 값은 전부 글자다 — 이 줄에 색을 쓰지 않는 이유는 위 주석에 있다. */
function Item({ label, value }: { label: string; value: string }) {
  return (
    <div className="cn__item">
      <span className="cn__item-label">{label}</span>
      <span className="cn__item-value">{value}</span>
    </div>
  );
}

/** 구성요소 카드 하나. */
function Card({ component, samples }: { component: OpsComponent; samples: (number | null)[] }) {
  const { name, health, latencyMs, note, facts } = component;
  return (
    <article className="cn__card" data-health={health}>
      <header className="cn__card-head">
        <h2>{name}</h2>
        {/* 색 + 글자. 색만으로 말하는 자리를 이 화면에 두지 않는다(토큰 규칙 3). */}
        <span className="cn__chip" data-health={health}>{HEALTH_LABEL[health]}</span>
      </header>

      <p className="cn__latency">
        {/* ❗못 잰 것과 0ms 는 다르다. 계약이 그 둘을 가르려고 nullable 로 왔다. */}
        {latencyMs === null ? "왕복 — (안 쟀어요)" : `왕복 ${latencyMs}ms`}
      </p>

      {/* note 는 정상이면 없다. 있으면 **카드에서 제일 먼저 읽혀야 하는 문장**이다 —
          「무엇을 고쳐야 하는가」가 여기 들어 있다. */}
      {note && <p className="cn__note">{note}</p>}

      {facts.length > 0 ? (
        <dl className="cn__facts">
          {facts.map((f) => (
            <div key={f.label}>
              <dt>{f.label}</dt>
              <dd>{f.value}</dd>
            </div>
          ))}
        </dl>
      ) : (
        /* facts 가 비는 것은 **측정이 거기까지 못 갔다**는 뜻이다(연결 실패·측정 실패).
           빈칸으로 두면 「볼 게 없다」로 읽히므로 그 사실을 적는다. */
        <p className="cn__facts-empty">잰 값이 없어요 — 위 문장이 그 이유예요.</p>
      )}

      <Trend samples={samples} />
    </article>
  );
}

/**
 * 왕복 시간 추이. **이 화면을 연 뒤의 표본만** 들고 있다(서버에 시계열을 안 쌓는다).
 *
 * 표본이 둘 미만이면 안 그린다 — 막대 하나는 추이가 아니라 같은 숫자를 한 번 더 적는 것이다.
 */
function Trend({ samples }: { samples: (number | null)[] }) {
  const measured = samples.filter((v): v is number => v !== null);
  if (measured.length < 2) return null;
  const max = Math.max(1, ...measured);
  const label = `왕복 시간 추이 — 최근 ${samples.length}회, 최대 ${max}ms`;

  return (
    <div className="cn__trend" role="img" aria-label={label} title={label}>
      {samples.map((v, i) => (
        <span
          key={i}
          className={v === null ? "cn__bar cn__bar--gap" : "cn__bar"}
          /* 못 잰 자리는 높이를 안 준다 — 0 으로 그리면 「빠르다」로 읽힌다. */
          style={v === null ? undefined : { height: `${Math.max(8, (v / max) * 100)}%` }}
        />
      ))}
    </div>
  );
}
