package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 저장 파일명이 <b>바이트</b> 한도를 넘지 않는다. 소유: 강희진 (#527 후속)
 *
 * <h2>왜 이 파일이 있나 — 로컬에서 초록인 채로 배포가 깨졌다</h2>
 *
 * <p>{@code safeFilename} 이 처음에 <b>글자 수</b>로 120 을 잘랐다. 한글은 UTF-8 로 세
 * 바이트라 그게 360 바이트이고, 리눅스 ext4·overlayfs 의 파일명 한도가 <b>255 바이트</b>다.
 * 그래서 긴 한글 파일명을 올리면 {@code ENAMETOOLONG} → {@code UncheckedIOException} →
 * 운영자가 <b>500</b> 을 받았다.
 *
 * <p>❗<b>macOS 에서는 안 드러난다.</b> 실측으로 갈렸다(2026-09-08).
 *
 * <pre>
 *   macOS(APFS)   364 바이트 파일명 생성 성공   → 로컬 전건 초록
 *   리눅스(ext4)   255 바이트 초과 거부          → CI 에서만 빨강
 * </pre>
 *
 * <p>실제로 그렇게 났다 — {@code #527} 이 머지된 뒤 {@code #529} 의 CI(리눅스)가
 * {@code DocumentUploadWiringTest} 의 «긴 파일명이 500 이 아니다» 를 빨갛게 냈고, 같은
 * 테스트가 macOS 에서는 통과했다. 그 테스트는 <b>결과</b>(500 이 아니다)를 재므로 플랫폼에
 * 따라 답이 갈린다. 이 파일은 <b>원인</b>(바이트 길이)을 재므로 어디서 돌려도 같은 답이다.
 *
 * <p>이 레포가 반복해서 밟은 양식이다 — {@code #37}(컨테이너에 {@code data/} 가 없다) ·
 * {@code #433}(마운트 누락) · {@code #252}(개방 모드 지도). <b>로컬에만 있는 조건이 검사를
 * 통과시킨다.</b>
 */
@DisplayName("업로드 저장 파일명: 바이트 한도 (#527 후속)")
class UploadedFilenameByteLimitTest {

    /** 리눅스 ext4·overlayfs 한도. 이 값을 넘기면 그 파일시스템이 이름을 거부한다. */
    private static final int LINUX_NAME_MAX_BYTES = 255;

    @Test
    @DisplayName("❗긴 한글 파일명이 255 바이트를 안 넘는다 — 글자 수로 자르면 리눅스에서 500 이다")
    void aLongKoreanNameStaysUnderTheFilesystemLimit() {
        String name = "가".repeat(300) + ".pdf";

        String safe = UploadedDocumentStore.safeFilename(name);

        assertThat(safe.getBytes(StandardCharsets.UTF_8).length)
                .as("한글 300 자는 900 바이트다 — 글자 수로 120 을 자르면 360 바이트이고 "
                        + "리눅스 ext4 가 거부한다(ENAMETOOLONG → 업로드 500)")
                .isLessThanOrEqualTo(LINUX_NAME_MAX_BYTES);
    }

    @Test
    @DisplayName("❗글자 중간에서 자르지 않는다 — 깨진 코드포인트가 남으면 그 이름도 거부된다")
    void truncationNeverSplitsACodePoint() {
        String safe = UploadedDocumentStore.safeFilename("가".repeat(300) + ".pdf");

        assertThat(safe)
                .as("바이트로 잘라 붙이면 깨진 문자가 남고 화면에서 U+FFFD 가 된다")
                .doesNotContain("�");
        // 왕복해도 같은 값이면 코드포인트 경계에서 잘렸다는 뜻이다.
        assertThat(new String(safe.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(safe);
    }

    @Test
    @DisplayName("★ 뒤를 남긴다 — 확장자와 회차번호가 뒤에 있어 그쪽이 사람에게 유용하다")
    void theTailIsKeptSoTheExtensionSurvives() {
        String safe = UploadedDocumentStore.safeFilename("가".repeat(300) + "_4181.pdf");

        assertThat(safe).endsWith("_4181.pdf");
    }

    @Test
    @DisplayName("짧은 이름은 그대로 둔다 — 한도 안이면 손대지 않는다")
    void shortNamesAreUntouched() {
        assertThat(UploadedDocumentStore.safeFilename("els_kiwoom_4181.pdf"))
                .isEqualTo("els_kiwoom_4181.pdf");
        assertThat(UploadedDocumentStore.safeFilename("상품설명서.pdf"))
                .isEqualTo("상품설명서.pdf");
    }

    @Test
    @DisplayName("❗ASCII 한도도 같이 잰다 — 바이트로 바꾸면서 글자 기준이 느슨해질 수 있다")
    void asciiNamesAreAlsoBounded() {
        String safe = UploadedDocumentStore.safeFilename("a".repeat(500) + ".pdf");

        assertThat(safe.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(LINUX_NAME_MAX_BYTES);
        assertThat(safe).endsWith(".pdf");
    }
}
