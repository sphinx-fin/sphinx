/**
 * 사용 가이드 (`/guide`). 소유: 오준서.
 *
 * ── 화면 번호를 안 받는 이유 ────────────────────────────────────────────────
 *
 * `pages/` 의 다른 파일은 전부 `S0n_` 으로 시작한다. 이건 안 붙인다 — 명세 8절의 화면
 * 목록은 S-01~S-08 이고 **여기 새 번호를 얹으면 명세에 없는 화면이 명세 화면인 척**
 * 하게 된다. 예전 S-00(심사·시연용 목차, 삭제됨)이 "흐름 밖" 번호를 받았던 것과는 사정이
 * 다르다: 저건 그래도 사람이 조작하는 화면이었고, 이건 **다른 화면들에 대해 쓴 문서**다.
 * 그래서 번호 대신 이름이다.
 *
 * ── 예전 목차(S-00)와 무엇이 달랐나 ──────────────────────────────────────────
 *
 * 목차는 **어디로 가는지**를 말했고 여기는 **가서 무엇을 하는지**를 말한다. 목차의 카드는
 * 한 줄 설명 + 전제 + 링크라 이미 화면을 아는 사람에게 쓸모가 있었고, 처음 여는 사람은
 * "세션 시작" 을 눌러 놓고 어느 칸을 채워야 하는지에서 멈췄다. 그 자리를 캡처가 메운다 —
 * 글로 "상품을 고르고 설문을 채워요" 라고 쓰는 것보다 그 화면을 한 장 보여주는 쪽이 짧다.
 *
 * ── 캡처는 `src/assets/guide/` 에 둔다 (`public/` 이 아니다) ────────────────
 *
 * ❗`public/guide/` 에 두면 **배포에서만 이 화면이 죽는다.** `app.conf` 의 라우팅이
 * `try_files $uri $uri/ /index.html` 이라, 같은 이름의 디렉토리가 있으면 `/guide` 요청이
 * SPA 로 떨어지기 전에 `$uri/` 에 걸린다 — 디렉토리 인덱스가 없으니 403 이고, `vite dev`
 * 는 그 폴백이 없어 로컬에서는 멀쩡히 뜬다. 전역 규칙이 목업 경로에서 경고하는 그
 * 모양("로컬은 되는데 배포에서만") 그대로다.
 *
 * `import` 로 들여오면 번들러가 `/assets/<해시>.jpg` 로 내보내므로 라우트와 이름이 겹칠
 * 일이 없고, 캡처를 갱신했을 때 캐시도 알아서 깨진다.
 *
 * ── 캡처는 alpha 실화면이다 ─────────────────────────────────────────────────
 *
 * 목업이 아니라 `sphinxfin.duckdns.org` 에서 실제 세션 하나를 S-02→S-07 로 통과시키며
 * 찍었다. 그래서 **화면이 바뀌면 이 캡처는 낡는다** — 문구를 고칠 때 같이 다시 찍는다.
 * 판정이 적색인 세션인 것도 의도다: 녹색 세션으로 찍으면 S-06 이 빈 화면이 되고, 이 제품이
 * 무엇을 막는 물건인지가 가이드에서 사라진다.
 *
 * ── 톤 ─────────────────────────────────────────────────────────────────────
 *
 * 제품 화면과 같은 문법이다(`tokens.css` 의 "형태는 토스 문법"). 한 단계에 할 일 하나,
 * 문장은 짧게, 겁주지 않고 그냥 다음에 누를 것을 말한다. **판정 3색은 쓰지 않는다** —
 * 이 화면에는 판정이 없다(토큰 규칙 1). 캡처 안의 녹·황·적은 그 화면의 것이지 이 화면의
 * 것이 아니라서, 캡처를 감싼 액자는 무채색으로 둔다.
 */
import { Link } from "react-router-dom";
import { useElderlyMode } from "../hooks/useElderlyMode";
import s02Shot from "../assets/guide/s02-session-start.jpg";
import s03Shot from "../assets/guide/s03-interview.jpg";
import s04Shot from "../assets/guide/s04-simulator.jpg";
import s05Shot from "../assets/guide/s05-judgment.jpg";
import s05EvidenceShot from "../assets/guide/s05-judgment-evidence.jpg";
import s06Shot from "../assets/guide/s06-override.jpg";
import s07Shot from "../assets/guide/s07-report.jpg";
import s08Shot from "../assets/guide/s08-dashboard.jpg";
import s08ItemsShot from "../assets/guide/s08-dashboard-items.jpg";
import "./Guide.css";

/** 창구에서 **누가 이 화면을 잡는가**(역할≠RBAC). */
type Audience = "판매자" | "고객" | "관리자" | "준법감시";

interface Shot {
  src: string;
  /** 캡처가 안 뜰 때 이 문장만 남는다. "스크린샷" 이라고 쓰지 않고 무엇이 보이는지 쓴다. */
  alt: string;
  /** 이 캡처에서 봐야 할 곳. 비워도 된다. */
  caption?: string;
}

/**
 * 캡처의 원본 크기. `<img>` 에 그대로 실어 브라우저가 **자리를 미리 잡게 한다.**
 *
 * 없으면 지연 로딩(`loading="lazy"`)이 끝날 때마다 그 아래가 통째로 밀린다 — 이 화면은
 * 캡처 9장이 세로로 늘어선 긴 문서라, 읽는 중에 문단이 발밑에서 튀는 것이 그대로 보인다.
 *
 * ❗**한 벌인 이유는 캡처를 한 창에서 다 찍었기 때문이다**(1518×784). 크기가 다른 캡처를
 * 넣게 되면 이 상수를 `Shot` 안으로 옮긴다 — 그대로 두면 그 한 장만 비율이 틀어진다.
 */
const SHOT_W = 1518;
const SHOT_H = 784;

interface Step {
  /** 명세 8절의 화면 번호. 가이드의 순서 번호와 다르다 — 그건 렌더에서 센다. */
  id: string;
  name: string;
  audience: Audience;
  route: string;
  /** 이 화면에서 하는 일 한 문장. 제목이라 길면 안 된다. */
  headline: string;
  shots: readonly Shot[];
  /** "이렇게 하면 돼요" — 순서대로 누를 것. 3~4개를 넘기지 않는다. */
  todo: readonly string[];
  /** 몰라서 멈추는 자리 하나. 경고가 아니라 미리 알려주는 말이다. */
  note: string;
}

const STEPS: readonly Step[] = [
  {
    id: "S-02",
    name: "세션 시작",
    audience: "판매자",
    route: "/",
    headline: "상품을 고르고 검사를 하나 열어요",
    shots: [
      {
        src: s02Shot,
        alt: "대상 상품과 판매 채널을 고르고, 연령대·가입금액 구간·투자 경험을 선택하는 세션 시작 화면",
        caption: "고객 속성은 구간으로만 받아요. 이름을 넣는 칸은 아예 없어요.",
      },
    ],
    todo: [
      "계약할 상품과 판매 채널을 골라요.",
      "연령대·가입금액 구간·투자 경험을 고르고, 설문에 답해요.",
      "「세션 만들기」를 누르면 세션 번호가 나와요. 다음 화면들은 이 번호로 열려요.",
    ],
    note: "이름·주민번호·전화번호를 넣는 칸은 만들지 않았어요. 넣을 수 없는 게 맞아요.",
  },
  {
    id: "S-03",
    name: "되말하기 인터뷰",
    audience: "고객",
    route: "/interview/:sid",
    headline: "여기서 태블릿을 고객님께 넘겨요",
    shots: [
      {
        src: s03Shot,
        alt: "낙인 배리어 항목을 묻는 질문과, 고객이 자기 말로 적은 답변이 들어간 인터뷰 화면. 위에 10개 중 3개 응답 완료 표시",
        caption: "위쪽 막대가 몇 개 남았는지 알려줘요.",
      },
    ],
    todo: [
      "질문을 읽고, 아는 만큼 본인 말로 적어요.",
      "잘 모르겠으면 「이 항목 건너뛰기」를 눌러도 돼요. 뒤에서 다시 설명해 드려요.",
      "글씨가 작으면 오른쪽 위 「큰 글씨」를 켜요.",
    ],
    note: "정답을 맞히는 시험이 아니에요. 대신 적어 드리는 것도 안 돼요 — 누가 어떻게 입력했는지가 기록에 남아요.",
  },
  {
    id: "S-04",
    name: "손실 시뮬레이터",
    audience: "고객",
    route: "/simulator/:sid",
    headline: "내 돈이 얼마가 되는지 금액으로 봐요",
    shots: [
      {
        src: s04Shot,
        alt: "가입 예정 금액 5,000만 원을 슬라이더로 맞추고, 최악·중간·최선 세 가지 결과가 금액으로 나란히 놓인 시뮬레이터 화면",
        caption: "왼쪽이 최악이에요. 5,000만 원이 2,532만 원이 된 구간이 실제로 있었어요.",
      },
    ],
    todo: [
      "슬라이더를 가입하실 금액에 맞춰요.",
      "세 카드를 왼쪽부터 같이 읽어요. 퍼센트가 아니라 원 단위 금액이에요.",
      "카드 아래 기간(예: 2007-07 ~ 2010-07)이 그 숫자가 나온 실제 구간이에요.",
    ],
    note: "예측이 아니라 과거에 있었던 일이에요. 카드의 비중도 그 구간에서 나온 빈도이지 앞으로의 확률이 아니에요.",
  },
  {
    id: "S-05",
    name: "판정 결과",
    audience: "판매자",
    route: "/judgment/:sid",
    headline: "신호등과 근거를 같이 봐요",
    shots: [
      {
        src: s05Shot,
        alt: "보류(적색) 신호와 발화한 룰 R-01·R-02, 그 아래 항목별 이해도 목록이 있는 판정 결과 화면",
        caption: "위쪽 「발화한 룰」이 이 신호를 만든 이유예요.",
      },
      {
        src: s05EvidenceShot,
        alt: "오해로 채점된 항목 카드. 고객이 실제로 한 말과 채점 기준이 나란히 있고 오른쪽에 재설명 버튼이 있다",
        caption: "항목마다 고객이 한 말과 채점 기준이 나란히 있어요. 오해 항목에는 「재설명」이 붙어요.",
      },
    ],
    todo: [
      "신호등을 보고, 바로 아래 「발화한 룰」에서 이유를 확인해요.",
      "「오해」가 붙은 항목의 「재설명」을 눌러 다시 설명해요. 고객이 다시 답하면 재검증돼요.",
      "더 볼 게 없으면 맨 아래 「판정 확정」을 눌러요.",
    ],
    note: "확정하면 되돌릴 수 없어요. 확정 뒤에는 재설명·재검증으로 못 돌아가요.",
  },
  {
    id: "S-06",
    name: "적색 승인",
    audience: "관리자",
    route: "/override/:sid",
    headline: "그래도 진행해야 하면 사유를 남겨요",
    shots: [
      {
        src: s06Shot,
        alt: "적색 진행 요청 화면. 사유를 30자 이상 적으면 승인 요청 버튼이 켜진다",
        caption: "30자를 넘겨야 「승인 요청」이 켜져요.",
      },
    ],
    todo: [
      "어떤 항목을 어떻게 보완했는지 사유에 적어요.",
      "「승인 요청」을 누르면 관리자에게 넘어가요.",
      "관리자가 승인해야 진행할 수 있어요.",
    ],
    note: "판정을 확정한 뒤에만 열려요. 적은 사유는 기록으로 남고 준법감시에 그대로 통보돼요.",
  },
  {
    id: "S-07",
    name: "이해 기록 리포트",
    audience: "판매자",
    route: "/report/:sid",
    headline: "오늘 무엇을 설명했는지 문서로 남겨요",
    shots: [
      {
        src: s07Shot,
        alt: "리포트 번호·발행 시각·SHA-256 내용 해시가 있고, 미리보기와 내려받기 버튼이 있는 발행 완료 화면",
        caption: "가운데 64자가 내용 해시예요. 교부한 문서를 나중에 대조하는 값이에요.",
      },
    ],
    todo: [
      "「리포트 발행」을 눌러요. 판정·재설명 이력이 문서로 조립돼요.",
      "「미리보기」로 내용을 확인하고 「내려받기」로 고객께 드려요.",
      "내용 해시는 자르지 말고 통째로 보관해요.",
    ],
    note: "같은 내용이면 다시 발행해도 해시가 같아요. 내용이 달라지면 새로 발행되지만, 이전 발행 기록도 그대로 남아요.",
  },
  {
    id: "S-08",
    name: "오해 지도 대시보드",
    audience: "준법감시",
    route: "/dashboard",
    headline: "어느 항목에서 다들 걸리는지 봐요",
    shots: [
      {
        src: s08Shot,
        alt: "오해율 27%, 표본 665건 같은 요약 숫자와 게이트 결정 분포 막대가 있는 대시보드 상단",
        caption: "위쪽 네 숫자가 요약이에요.",
      },
      {
        src: s08ItemsShot,
        alt: "항목별 오해율 목록. 전액손실 사례 50%, 발행사 신용위험 44% 순으로 막대가 늘어서 있다",
        caption: "위에 있는 항목일수록 설명 자료를 먼저 고쳐야 해요.",
      },
    ],
    todo: [
      "상품·연령대·채널로 좁혀 봐요.",
      "「항목별 오해율」 위쪽부터 읽어요. 거기가 설명이 안 먹히는 자리예요.",
      "「선행지표」 탭에서는 추세를 봐요.",
    ],
    note: "표본이 30건보다 적으면 「가려짐」으로 나와요. 적은 표본으로 특정 창구를 지목하지 않으려고 일부러 가려요.",
  },
];

export default function Guide() {
  const { elderly, toggle } = useElderlyMode();

  return (
    <main className="gd">
      <div className="gd__shell">
        <header className="gd__head">
          <p className="gd__eyebrow">사용 가이드</p>
          <h1 className="gd__title">
            창구에서 이렇게 쓰면 돼요
          </h1>
          <p className="gd__lede">
            계약 직전에 <b>고객이 상품을 정말 이해했는지</b> 확인하는 화면이에요. 화면 7개를
            순서대로 지나가면 되고, 중간에 <b>태블릿을 고객님께 한 번 넘겨요.</b>
          </p>
          <div className="gd__headrow">
            <button
              type="button"
              role="switch"
              aria-checked={elderly}
              className={`gd__toggle ${elderly ? "gd__toggle--on" : ""}`}
              onClick={toggle}
            >
              큰 글씨 {elderly ? "켜짐" : "꺼짐"}
            </button>
          </div>
        </header>

        {/* ── 시작 전에 ────────────────────────────────────────────────────── */}
        <section className="gd__card" aria-labelledby="gd-before">
          <h2 className="gd__section" id="gd-before">
            먼저 세 가지만
          </h2>
          <ul className="gd__list">
            <li>
              <b>화면 하나에 할 일 하나예요.</b> 아래 큰 버튼만 누르면 다음으로 넘어가요.
            </li>
            <li>
              <b>세션 번호가 열쇠예요.</b> S-02 에서 만든 번호로 나머지 화면이 열려요.
              번호가 없으면 아래 1단계(세션 시작)에서 하나 만들면 돼요.
            </li>
            <li>
              <b>색은 판정에만 써요.</b> 녹색·노란색·빨간색이 보이면 그건 검사 결과지
              버튼이나 장식이 아니에요. 색 옆에 항상 글자가 같이 있어요.
            </li>
          </ul>
        </section>

        {/* ── 단계 ─────────────────────────────────────────────────────────── */}
        <ol className="gd__steps">
          {STEPS.map((step, i) => (
            <StepCard key={step.id} step={step} index={i + 1} />
          ))}
        </ol>

        {/* ── 자주 막히는 곳 ───────────────────────────────────────────────── */}
        <section className="gd__card gd__card--note" aria-labelledby="gd-stuck">
          <h2 className="gd__section" id="gd-stuck">
            여기서 자주 막혀요
          </h2>
          <dl className="gd__faq">
            <div>
              <dt>화면이 열리지 않아요</dt>
              <dd>
                주소에 세션 번호가 들어가는 화면이에요. 위 1단계(세션 시작)에서 먼저 세션을
                만들고, 그 번호로 주소를 채워야 열려요.
              </dd>
            </div>
            <div>
              <dt>질문이 안 나와요</dt>
              <dd>판정을 확정한 세션이에요. 확정 뒤에는 더 묻지 않아요.</dd>
            </div>
            <div>
              <dt>리포트를 열었는데 내용이 비어 있어요</dt>
              <dd>
                대시보드 집계에 쓰는 합성 세션이라 그래요. 직접 만든 세션으로 열어 보세요.
              </dd>
            </div>
            <div>
              <dt>적색인데 「승인 요청」이 안 눌려요</dt>
              <dd>사유가 30자를 넘어야 켜져요. 판정 확정도 먼저 해야 해요.</dd>
            </div>
          </dl>
        </section>
      </div>
    </main>
  );
}

/**
 * 단계 한 장.
 *
 * 캡처를 **제목 바로 아래**에 두고 할 일 목록을 그 밑에 둔다. 순서를 바꿔 글을 먼저 두면
 * 읽는 사람이 글과 그림을 번갈아 오가야 하는데, 이 화면은 처음 보는 화면을 설명하는
 * 자리라 그림이 먼저 와야 글의 "여기" 가 어디인지 정해진다.
 */
function StepCard({ step, index }: { step: Step; index: number }) {
  // 세션 번호를 미리 알 방법이 없다 — 직접 채워야 여는 화면은 링크를 안 그린다.
  const openable = !step.route.includes(":sid");

  return (
    <li className="gd__step">
      <div className="gd__step-head">
        <span className="gd__step-num" aria-hidden="true">
          {index}
        </span>
        <div className="gd__step-heading">
          <p className="gd__step-meta">
            <span className="sr-only">화면 </span>
            {step.id}
            <span className="gd__badge">{step.audience}</span>
          </p>
          <h2 className="gd__step-title">{step.headline}</h2>
          <p className="gd__step-name">{step.name}</p>
        </div>
      </div>

      <div className="gd__shots">
        {step.shots.map((shot) => (
          <figure className="gd__shot" key={shot.src}>
            {/* 첫 장까지 지연 로딩하면 화면을 열자마자 액자만 보이는 순간이 생긴다.
                두 번째 장부터는 스크롤해야 닿으므로 미룬다. */}
            <img
              className="gd__shot-img"
              src={shot.src}
              alt={shot.alt}
              width={SHOT_W}
              height={SHOT_H}
              loading={index === 1 ? "eager" : "lazy"}
              decoding="async"
            />
            {shot.caption && <figcaption className="gd__shot-cap">{shot.caption}</figcaption>}
          </figure>
        ))}
      </div>

      <div className="gd__step-body">
        <h3 className="gd__todo-title">이렇게 하면 돼요</h3>
        <ol className="gd__todo">
          {step.todo.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ol>

        <p className="gd__note">{step.note}</p>

        <div className="gd__step-foot">
          {openable ? (
            <Link className="gd__open" to={step.route}>
              이 화면 열어보기
            </Link>
          ) : (
            <Link className="gd__open gd__open--ghost" to="/">
              세션 만들고 열어보기
            </Link>
          )}
          <code className="gd__route">{step.route}</code>
        </div>
      </div>
    </li>
  );
}
