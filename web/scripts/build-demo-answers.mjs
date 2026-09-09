#!/usr/bin/env node
/**
 * `data/demo_answers/*.json` → `web/src/lib/demoAnswers.generated.ts` (이슈 #539 ② 배선).
 * 소유: 오준서.
 *
 * `eval/tools/build_demo_answers.py`(정세현 · PR #544)가 라벨 코퍼스에서 뽑은 답지를 화면이
 * 쓸 수 있는 모듈로 옮긴다. 라벨이 바뀌면 `els.json` 이 바뀌고, 이 스크립트를 다시 돌리면
 * 화면 답지가 따라온다 — 손으로 옮기던 것을 걷어내는 것이 이 배선의 목적이다.
 *
 * ── ❗왜 «생성해서 커밋» 인가 — web 은 `data/` 를 못 읽는다 ──────────────────
 *
 * 두 가지가 동시에 걸린다.
 *
 *   ① **Docker 빌드 컨텍스트가 `web/` 이다.** `web/Dockerfile` 이 `COPY . .` 로 넣는 것은
 *      `web/` 뿐이라 이미지 안에 `data/` 가 없다. 화면이 `../../data/...` 를 import 하면
 *      **로컬 `vite build` 는 통과하고 배포 이미지 빌드만 깨진다** — 로컬에서 안 보이는
 *      종류의 실패다.
 *   ② **런타임 fetch 로 두면 답지가 URL 로 열린다.** 이 답지는 고객 화면(S-03)에 붙는
 *      물건이라 «꺼진 빌드의 번들에 문자열이 아예 없어야» 한다(`demoAnswers.ts` 머리말 ·
 *      CI 「데모 답지가 기본 빌드에 없어야 한다」). `dist/` 에 JSON 을 두면 그 성질이 사라진다.
 *
 * 그래서 산출물을 **레포에 커밋**한다(`data/timeseries` 와 같은 이유). 대신 낡을 수 있으므로
 * CI 가 `--check` 로 매번 대조한다. **`npm run build` 에는 걸지 않는다** — 걸면 이미지
 * 빌드가 `data/` 를 찾다가 죽는다(①).
 *
 * ── 무엇을 옮기고 무엇을 안 옮기나 ──────────────────────────────────────────
 *
 * `labeled`(사람 라벨 합의)만 여기서 온다. `written`·`devset` 은 생성기가 낼 수 있는 물건이
 * 아니라 `demoAnswers.ts` 에 손으로 남고, **겹치면 이쪽이 이긴다**(같은 파일의 `overlay`).
 * 생성기가 «U1 합의가 없는 항목은 키를 만들지 않는다»고 정해 뒀으므로(build_demo_answers.py
 * 머리말), 여기 없는 자리는 «비었다»가 아니라 «라벨이 없다»는 뜻이다.
 *
 * 사용법:
 *
 *     node web/scripts/build-demo-answers.mjs           # 생성본을 쓴다
 *     node web/scripts/build-demo-answers.mjs --check   # 낡았으면 1 로 죽는다 (CI)
 */
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SRC_DIR = join(ROOT, "data", "demo_answers");
const OUT = join(ROOT, "web", "src", "lib", "demoAnswers.generated.ts");

/** 화면이 쓰는 등급 둘. 생성기도 이 둘만 낸다(U2·U3 는 캡션에 안 들어간다). */
const GRADES = ["u1", "u4"];

function fail(msg) {
  console.error(`❗${msg}`);
  process.exit(1);
}

/** `data/demo_answers/*.json` 을 전부 읽어 항목ID → {u1?,u4?} 로 합친다. */
function read() {
  if (!existsSync(SRC_DIR)) fail(`${SRC_DIR} 가 없다 — eval/tools/build_demo_answers.py --write 가 먼저다.`);
  const files = readdirSync(SRC_DIR).filter((f) => f.endsWith(".json")).sort();
  if (files.length === 0) fail(`${SRC_DIR} 에 JSON 이 없다.`);

  /** @type {Record<string, {sourceFile: string, grades: Record<string, {text: string, sampleId?: string}>}>} */
  const merged = {};
  for (const file of files) {
    const raw = JSON.parse(readFileSync(join(SRC_DIR, file), "utf-8"));
    const answers = raw.answers;
    if (!answers || typeof answers !== "object") {
      fail(`${file} 에 answers 객체가 없다 — 생성기 형식이 바뀌었으면 이 스크립트도 같이 고친다.`);
    }
    for (const [itemId, pair] of Object.entries(answers)) {
      // ❗**항목ID 가 두 파일에 겹치면 죽는다.** 조용히 덮으면 어느 파일이 이겼는지가
      //   산출물에 안 남고, 상품이 늘 때(변액 라벨링) 정확히 그 자리에서 갈린다.
      if (merged[itemId]) fail(`${itemId} 가 ${merged[itemId].sourceFile} 와 ${file} 양쪽에 있다.`);
      const grades = {};
      for (const g of GRADES) {
        const entry = pair[g];
        if (!entry) continue;
        if (entry.source !== "labeled") {
          // 생성기는 라벨 합의분만 낸다. 다른 source 가 오면 규약이 바뀐 것이고,
          // 그대로 옮기면 `written` 을 덮어쓰는 우선순위가 틀린 뜻이 된다.
          fail(`${itemId}.${g} 의 source 가 "${entry.source}" 다 — 생성본은 labeled 만 담는다.`);
        }
        if (typeof entry.text !== "string" || entry.text.length === 0) fail(`${itemId}.${g} 에 text 가 없다.`);
        grades[g] = { text: entry.text, sampleId: entry.sampleId };
      }
      if (Object.keys(grades).length > 0) merged[itemId] = { sourceFile: file, grades };
    }
  }
  return { files, merged };
}

function render({ files, merged }) {
  const ids = Object.keys(merged).sort();
  const lines = [];
  lines.push("/**");
  lines.push(" * ❗**자동 생성 — 손으로 고치지 않는다.**");
  lines.push(" *");
  lines.push(` * 생성기  web/scripts/build-demo-answers.mjs`);
  lines.push(` * 입력    ${files.map((f) => `data/demo_answers/${f}`).join(" · ")}`);
  lines.push(` *         (그 파일은 eval/tools/build_demo_answers.py 가 라벨 코퍼스에서 낸다 — PR #544)`);
  lines.push(" *");
  lines.push(" * 라벨 합의분(`labeled`)만 담는다. 루브릭에서 «작성한» 예시와 dev set 기대값은");
  lines.push(" * `demoAnswers.ts` 에 손으로 남고, 같은 자리가 겹치면 **이 파일이 이긴다**.");
  lines.push(" * 고치려면 라벨을 고치고 두 생성기를 차례로 돌린다(이슈 #539 ②).");
  lines.push(" */");
  lines.push('import type { DemoAnswerPair } from "./demoAnswers";');
  lines.push("");
  lines.push("/** 항목ID → 라벨 합의 답지. **없는 등급은 키가 없다** — 「비었다」가 아니라 「라벨이 없다」다. */");
  lines.push("export const LABELED_ANSWERS: Readonly<Record<string, DemoAnswerPair>> = {");
  for (const id of ids) {
    const { grades } = merged[id];
    lines.push(`  ${JSON.stringify(id)}: {`);
    for (const g of GRADES) {
      const entry = grades[g];
      if (!entry) continue;
      if (entry.sampleId) lines.push(`    // ${entry.sampleId}`);
      lines.push(`    ${g}: {`);
      lines.push(`      text: ${JSON.stringify(entry.text)},`);
      lines.push(`      source: "labeled",`);
      lines.push(`    },`);
    }
    lines.push("  },");
  }
  lines.push("};");
  lines.push("");
  return lines.join("\n");
}

const wanted = render(read());
const checkOnly = process.argv.includes("--check");
const current = existsSync(OUT) ? readFileSync(OUT, "utf-8") : null;

if (checkOnly) {
  if (current !== wanted) {
    fail(
      "web/src/lib/demoAnswers.generated.ts 가 data/demo_answers/ 와 다르다 — " +
        "`node web/scripts/build-demo-answers.mjs` 를 돌려 커밋한다.",
    );
  }
  console.log("데모 답지 생성본이 최신이다.");
} else if (current === wanted) {
  console.log("데모 답지 생성본에 바뀐 것이 없다.");
} else {
  writeFileSync(OUT, wanted, "utf-8");
  console.log(`→ web/src/lib/demoAnswers.generated.ts`);
}
