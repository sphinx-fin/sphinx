/// <reference types="vite/client" />

/**
 * 빌드 시점 치환 상수. 소유: 오준서.
 *
 * `vite.config.ts` 의 `define` 이 이 이름을 **리터럴 `true`/`false` 로 바꾼다.** 값이 아니라
 * 리터럴인 것이 요점이다 — `false && <캡션>` 이 접혀야 `lib/demoAnswers` 가 번들에서 빠진다
 * (S03_Interview.tsx 설계 판단 ⑤ · 이슈 #539). 런타임에 이 이름은 존재하지 않는다.
 */
declare const __DEMO_CAPTIONS__: boolean;
