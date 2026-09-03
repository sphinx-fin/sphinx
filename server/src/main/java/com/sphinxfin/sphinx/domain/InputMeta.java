package com.sphinxfin.sphinx.domain;

/**
 * F-INT-003 입력 메타데이터 — <b>무엇을 말했나가 아니라 어떻게 입력했나</b>. (이슈 #325)
 *
 * @param firstKeystrokeDelayMs 질문이 뜨고 첫 타이핑까지
 * @param totalInputMs          첫 타이핑부터 제출까지
 * @param pasteDetected         붙여넣기가 있었나
 * @param backspaceCount        지운 횟수
 * @param charCount             공백을 뺀 글자 수
 * @param elderlyMode           고령자 모드였나 — 지연·입력시간의 해석 기준이 달라진다
 *
 * <h2>왜 이 값이 이 제품에 특별한가</h2>
 *
 * <p><b>붙여넣기로 채운 되말하기는 되말하기가 아니다.</b> 판매자가 대신 입력했거나 화면에
 * 뜬 설명을 그대로 복사한 것이고, 그게 기획서 7-4 가 막으려는 행동이다. <b>발화 내용만
 * 보면 완벽한 U1 로 채점된다</b> — 텍스트로는 구분이 안 되고 입력 방식으로만 구분된다.
 *
 * <p>{@code ai-service} 의 복창 판정({@code cap_confidence_if_echoed})은 <b>루브릭 문면을
 * 따라 쓴 경우</b>만 잡는다. 붙여넣기는 그 앞단의 더 굵은 신호다.
 *
 * <h2>측정이지 판정이 아니다 (P1)</h2>
 *
 * <p>게이트에 물리지 않는다. <b>기록에 남기고 집계에서 본다.</b> {@code evidence/} 는
 * append-only 라 <b>지금부터 쌓이는 것만 남고</b>, 늦을수록 복구가 안 된다 —
 * {@code #295} 가 {@code unmeasured} 로 겪은 자리와 같다.
 *
 * <h2>❗판매자 화면에 안 나간다</h2>
 *
 * <p>판매자가 <i>"붙여넣기가 잡힌다"</i> 를 알면 손으로 옮겨 적게 되고, 그러면 <b>신호만
 * 죽고 행동은 그대로다.</b> {@code #144} 가 {@code misconceptionType} 을 판매자 뷰에서 뺀
 * 것과 같은 결이고, {@code JudgmentViewFieldsTest} 가 그 자리를 이름으로 잠근다.
 *
 * <h2>레코드인 것이 화이트리스트다</h2>
 *
 * <p>요청은 {@code Map<String, Object>} 로 받으면 <b>화면이 나중에 실어 보내는 것이 그대로
 * 불변 기록에 박힌다.</b> 타입으로 받으면 모르는 키가 애초에 안 들어온다 — 그리고 여기
 * 있는 것은 전부 숫자·불리언이라 PII 가 들어올 자리가 없다(P3).
 */
public record InputMeta(long firstKeystrokeDelayMs, long totalInputMs, boolean pasteDetected,
                        int backspaceCount, int charCount, boolean elderlyMode) {}
