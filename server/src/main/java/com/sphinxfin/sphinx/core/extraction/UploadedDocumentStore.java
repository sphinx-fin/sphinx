package com.sphinxfin.sphinx.core.extraction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 업로드된 상품 문서의 바이트가 사는 곳 (F-EXT-001 · 이슈 #521). 소유: 강희진
 *
 * <h2>왜 파일시스템인가</h2>
 *
 * <p>ai-service {@code /internal/parse} 는 <b>경로를 받는다</b>({@code document_path}, 그쪽
 * {@code ParseRequest}). 그래서 파일이 <b>server 와 ai-service 둘 다 보이는 자리</b>에
 * 있어야 하고, 그 자리가 {@code sphinx.documents.data-dir}(= ai-service
 * {@code SPHINX_DATA_DIR}) 아래 {@code uploads/} 다. 바이트를 요청 본문에 실어 보내는
 * 갈래는 ai-service 의 PII 재검사 미들웨어가 JSON 본문만 훑기 때문에 그 경로만 P3
 * 재검사 밖으로 나간다 — 문서 한 편을 통째로 나르는 경로에서 그 방어선을 끄는 것은
 * 방향이 반대다(#521 의 ③).
 *
 * <p>❗<b>전용 환경변수를 새로 만들지 않는다.</b> {@code uploads/} 는 이미 있는 뿌리
 * 아래의 하위 디렉토리다 — ai-service {@code parsing.documents_root()} 가 같은 이유로
 * knob 을 안 만들어 뒀고, 그래서 그쪽은 이 배선에 고칠 줄이 없다. 배포에서 이 디렉토리는
 * <b>이름 있는 도커 볼륨</b>({@code sphinx_uploads})으로, server 는 쓰기·ai-service 는
 * 읽기로 붙는다. {@code data/} 본체가 읽기 전용인 것(결정 7.8 · {@code VERSION} sha256
 * 고정)은 그대로다 — 고정된 코퍼스와 사람이 올린 것이 같은 마운트에 섞이지 않는다.
 *
 * <h2>경로는 내용이 정한다</h2>
 *
 * <pre>
 *   uploads/&lt;sha256&gt;/&lt;정제한 파일명&gt;
 * </pre>
 *
 * <p>같은 바이트는 같은 경로다 — 재업로드가 디렉토리를 늘리지 않고, 덮어써도 내용이
 * 같으므로 <b>파스 결과가 안 바뀐다</b>(P2). 내용이 다르면 파일명이 같아도 다른
 * 디렉토리라 서로를 밟지 않는다. 업로더가 준 파일명을 그대로 디렉토리로 쓰면
 * {@code ../} 나 동명이인 문서가 문제가 되는데, 이름을 경로 결정에서 뺀 것이 그 답이다.
 */
@Service
@Slf4j
public class UploadedDocumentStore {

    /** {@code data-dir} 아래 업로드본이 사는 하위 디렉토리. 사전적재 {@code documents/} 와 가른다. */
    static final String UPLOADS = "uploads";

    /** 파일명에서 살릴 문자. 나머지는 {@code _} 로 접는다 — 경로 구분자·제어문자가 여기서 죽는다. */
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9가-힣._-]+");

    /** 상품ID 슬러그에서 살릴 문자. ai-service {@code derive_document_id} 와 같은 모양이다. */
    private static final Pattern UNSAFE_SLUG = Pattern.compile("[^a-z0-9]+");

    /** 슬러그 최대 길이. productId 는 여러 테이블의 varchar(255) 를 타므로 여유를 둔다. */
    private static final int SLUG_MAX = 40;

    private final Path dataDir;

    /**
     * {@link ProductDocuments} 와 <b>같은 값</b>을 읽는다 — 쓰는 자리와 읽는 자리가 갈리면
     * 업로드는 되는데 원문 조회가 404 인 상태가 된다. 빈 값을 설정 오류로 세우는 이유도 그쪽과 같다.
     */
    public UploadedDocumentStore(@Value("${sphinx.documents.data-dir}") String dataDir) {
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException(
                    "sphinx.documents.data-dir 이 비어 있다. 환경변수 SPHINX_DATA_DIR 을 빈 값으로 "
                    + "두면 기본값이 적용되지 않는다 — 지우거나 실제 경로를 넣는다.");
        }
        this.dataDir = Path.of(dataDir);
    }

    /** 저장 결과 — DB 에 적을 값들. {@code documentPath} 는 {@code data-dir} 상대다. */
    public record Stored(String documentPath, String filename, String sha256, long sizeBytes) {}

    /**
     * 바이트를 {@code uploads/&lt;sha256&gt;/&lt;파일명&gt;} 에 쓴다.
     *
     * <p>같은 내용이 이미 있으면 <b>다시 쓴다</b> — 건너뛰지 않는다. 내용이 같으므로 결과가
     * 같고, 반면 "있으면 건너뛴다" 는 반쯤 쓰인 파일(앞선 요청이 도중에 죽은 경우)을 영구히
     * 남긴다. 임시파일에 쓰고 원자적으로 옮기므로 반쯤 쓰인 상태가 관측되지 않는다.
     *
     * @throws UncheckedIOException 디렉토리를 못 만들거나 못 쓸 때(→ 500, 배포·권한 문제)
     */
    public Stored store(String originalFilename, byte[] bytes) {
        String filename = safeFilename(originalFilename);
        String sha256 = sha256(bytes);
        String relative = UPLOADS + "/" + sha256 + "/" + filename;
        // 기준 디렉토리 밖으로 나가지 않는 것을 쓰기 전에 확인한다 — sha256 과 정제된 파일명
        // 둘 다 우리가 만든 값이라 지금은 벗어날 수 없지만, 읽는 쪽(ProductDocuments)과 같은
        // 가드를 쓰는 자리에도 둬야 두 경계가 갈리지 않는다.
        Path target = ProductDocuments.resolveWithin(dataDir, relative);
        try {
            Files.createDirectories(target.getParent());
            // 같은 디렉토리 안 임시파일 → ATOMIC_MOVE. 다른 파일시스템을 건너면 원자성이
            // 보장되지 않으므로 임시파일을 /tmp 에 두지 않는다.
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".part");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            // 여기서 실패하는 것은 마운트·권한 문제다(볼륨이 ro 로 붙은 경우가 대표적)
            // — 잘못된 요청이 아니라 배포 설정이라 500 으로 나간다.
            throw new UncheckedIOException(
                    "업로드 문서를 저장하지 못했다(uploads 볼륨이 쓰기로 붙었는지 확인하라, "
                    + "#521): " + target, e);
        }
        log.info("업로드 문서 저장: path={} bytes={} sha256={}", relative, bytes.length, sha256);
        return new Stored(relative, filename, sha256, bytes.length);
    }

    /**
     * 상품ID 발급 — {@code doc-<파일명 슬러그>-<sha256 앞 8자>}.
     *
     * <p>❗<b>내용이 정한다.</b> 같은 파일을 두 번 올리면 같은 상품이고(업로드가 상품 목록을
     * 부풀리지 않는다), 문서가 한 바이트라도 다르면 다른 상품이다 — 게이트가 물을 항목이
     * 문서에서 나오므로 문서가 바뀐 것은 <b>판정 근거가 바뀐 것</b>이고, 같은 상품ID 로
     * 뭉치면 옛 세션의 판정이 새 문서의 항목을 근거로 삼은 것처럼 읽힌다.
     *
     * <p>모양을 사전적재 상품({@code doc-els-kiwoom-4181})과 같은 {@code doc-} 접두로
     * 맞춘다 — 결정 1.11 이 <i>"productId 는 파싱 산출물의 document_id 라 가명 대상이
     * 아니다"</i> 로 둔 그 어휘다. 표시명만 가명 대상이다.
     */
    public String issueProductId(String originalFilename, String sha256) {
        String stem = safeFilename(originalFilename);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        String slug = UNSAFE_SLUG.matcher(stem.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > SLUG_MAX) {
            slug = slug.substring(0, SLUG_MAX).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "unnamed";
        }
        return "doc-" + slug + "-" + sha256.substring(0, 8);
    }

    /**
     * 표시명 — 파일명에서 확장자를 떼고 구분자를 공백으로. {@code GET /products} 가 낸다.
     *
     * <p>가명 처리를 하지 않는다 — 운영자가 올린 파일의 이름이고, 무엇을 올렸는지 못 알아보면
     * 목록이 쓸모가 없다. 제출물 가명은 사전적재 데모 2종에 걸린 요구다(결정 1.11).
     */
    public String displayNameOf(String originalFilename) {
        String stem = safeFilename(originalFilename);
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        String name = stem.replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
        return name.isEmpty() ? "이름 없는 문서" : name;
    }

    /**
     * 저장에 쓸 파일명. 경로 구분자·{@code ..}·제어문자가 여기서 죽는다.
     *
     * <p>❗<b>업로더가 준 이름을 경로 결정에 쓰지 않는 것</b>이 첫 번째 방어이고(디렉토리는
     * sha256 이다) 이것이 두 번째다. 그래도 파일명은 남긴다 — 운영자가 무엇을 올렸는지
     * 알아야 하고, {@code Content-Disposition} 이 이 값을 낸다.
     */
    static String safeFilename(String original) {
        String base = original == null ? "" : original;
        // 브라우저·OS 에 따라 전체 경로가 올 수 있다. 마지막 조각만 쓴다.
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = UNSAFE_NAME.matcher(base).replaceAll("_");
        // 점만으로 된 이름(".", "..")은 정제 후에도 위험하다 — 통째로 바꾼다.
        base = base.replaceAll("^\\.+$", "");
        if (base.length() > 120) {
            base = base.substring(base.length() - 120);
        }
        return base.isEmpty() ? "document.pdf" : base;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 필수 알고리즘이다. 없으면 설정이 아니라 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
