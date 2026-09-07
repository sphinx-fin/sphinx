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
 * <h2>경로는 내용이 정한다 — 그리고 이름을 살린다</h2>
 *
 * <pre>
 *   uploads/&lt;sha256&gt;/&lt;정제한 파일명&gt;
 * </pre>
 *
 * <p>같은 바이트는 같은 경로다 — 재업로드가 디렉토리를 늘리지 않고, 덮어써도 내용이 같으므로
 * <b>파스 결과가 안 바뀐다</b>(P2). 내용이 다르면 파일명이 같아도 다른 디렉토리라 서로를
 * 밟지 않는다. <b>디렉토리가 sha256 이라</b> 업로더가 준 이름은 경로 결정에 관여하지 않고,
 * 그 이름은 {@link #safeFilename} 이 걷고 {@link ProductDocuments#resolveWithin} 이 뿌리
 * 밖을 다시 거부한다.
 *
 * <h2>❗왜 {@code uploads/&lt;sha256&gt;.pdf} 로 접지 않았나</h2>
 *
 * <p>이름을 아예 빼면 «{@code ..} 를 막는 코드가 필요 없다» 는 이점이 있고 그게 처음 제안된
 * 형태였다(#521). 그런데 <b>이 볼륨은 복구 수단이 없는 유일한 자산</b>이다(PR #532 가 그것을
 * 근거로 {@code external: true} 를 골랐다). DB 를 잃고 볼륨만 남은 상황에서
 * {@code 9f2a….pdf} 만 있는 트리는 <b>어느 파일이 무엇인지 아무도 모른다</b> — 이름을 살리는
 * 이유가 그것이다. 「무엇을 올렸는지」가 DB 한 곳에만 있으면 안 된다.
 *
 * <p>이름을 살려도 경로 안전은 <b>디렉토리가 해시</b>인 것으로 이미 성립한다 — 이름이
 * 경로의 <i>마지막 조각</i>이라 {@code ..} 로 올라갈 자리가 없고, 그래도 쓰기 전에 한 번 더
 * 본다. 즉 이점 하나(코드가 없다)를 이점 하나(사람이 읽을 수 있다)와 바꾼 것이고,
 * 복구 불가 자산 쪽에 무게를 뒀다.
 */
@Service
@Slf4j
public class UploadedDocumentStore {

    /** {@code data-dir} 아래 업로드본이 사는 하위 디렉토리. 사전적재 {@code documents/} 와 가른다. */
    static final String UPLOADS = "uploads";

    /**
     * 파일명에서 살릴 문자. 나머지는 {@code _} 로 접는다 — 경로 구분자·제어문자가 여기서 죽는다.
     *
     * <p>한글을 살린다({@code 가-힣}) — 운영자가 올리는 공시 문서 이름이 대개 한글이고,
     * 그걸 {@code _} 로 접으면 「무엇을 올렸는지」가 사라진다. 이 값은 저장 경로의 마지막
     * 조각이면서 {@code Content-Disposition} 문면이기도 하다.
     */
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9가-힣._-]+");

    /** 상품ID 슬러그에서 살릴 문자. ai-service {@code derive_document_id} 와 같은 모양이다. */
    private static final Pattern UNSAFE_SLUG = Pattern.compile("[^a-z0-9]+");

    /** 슬러그 최대 길이. productId 는 여러 테이블의 varchar(255) 를 타므로 여유를 둔다. */
    private static final int SLUG_MAX = 40;

    /**
     * productId 에 싣는 sha256 접두 길이 = <b>64비트</b>.
     *
     * <p>❗<b>8자(32비트)로는 부족하다</b>(PR #527 리뷰). productId 의 식별력이 사실상 이
     * 접두 하나이므로 — 슬러그는 한글 파일명에서 비고(아래 {@link #issueProductId} 참조) —
     * 32비트에서 생기는 충돌이 <b>다른 문서를 조용히 내주는 상태</b>로 굳는다. 64비트면
     * 그 확률이 실무에서 사라진다.
     */
    private static final int HASH_IN_ID = 16;

    /** DB 에 남길 원래 파일명 최대 길이. {@code varchar(255)} 보다 넉넉히 짧게 둔다. */
    private static final int DISPLAY_MAX = 200;

    /** 표시용 이름에서 지울 것 — 제어문자·개행. 응답 헤더가 갈라지는 것을 막는 최소치다. */
    private static final Pattern HEADER_UNSAFE = Pattern.compile("[\\p{Cntrl}]+");

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
     * <p>❗<b>배포에서 이 디렉토리는 이름 있는 도커 볼륨이다</b>({@code sphinx_uploads},
     * PR #532). 그쪽이 없으면 여기서 {@code EACCES} 로 실패하고 500 이 된다 — 볼륨과
     * 컨테이너 사용자 소유권이 맞아야 한다는 것이 그 PR 의 몫이다.
     *
     * @throws UncheckedIOException 디렉토리를 못 만들거나 못 쓸 때(→ 500, 배포·권한 문제)
     */
    public Stored store(String originalFilename, byte[] bytes) {
        String filename = safeFilename(originalFilename);
        String sha256 = sha256(bytes);
        // 디렉토리가 sha256 이라 업로더가 준 이름은 경로 결정에 관여하지 않는다. 이름은
        // 마지막 조각으로만 들어가고 safeFilename 이 걷은 뒤다 — 근거는 클래스 javadoc.
        String relative = UPLOADS + "/" + sha256 + "/" + filename;
        // 기준 디렉토리 밖으로 나가지 않는 것을 쓰기 전에 확인한다 — sha256 과 정제된 파일명
        // 둘 다 우리가 만든 값이라 지금은 벗어날 수 없지만, 읽는 쪽(ProductDocuments)과 같은
        // 가드를 쓰는 자리에도 둬야 두 경계가 갈리지 않는다.
        Path target = ProductDocuments.resolveWithin(dataDir, relative);
        try {
            Files.createDirectories(target.getParent());
            // ❗JVM 이 쓰기 도중에 죽으면 `.part` 가 남고 아무도 안 지운다. 디렉토리가
            // sha256 이라 여기 있을 정상 파일은 하나뿐이므로, 같은 내용을 다시 쓰는 이 자리가
            // 남은 조각을 치울 유일한 자연스러운 지점이다.
            try (var stale = Files.list(target.getParent())) {
                stale.filter(f -> f.getFileName().toString().startsWith(".upload-"))
                        .forEach(f -> {
                            try {
                                Files.deleteIfExists(f);
                                log.info("중단된 업로드 조각 정리: {}", f.getFileName());
                            } catch (IOException ignored) {
                                // 못 지워도 저장은 계속한다 — 용량만 샌다.
                            }
                        });
            }
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
        String hash = sha256.substring(0, HASH_IN_ID);
        // ❗**빈 슬러그를 상수로 채우지 않는다.** 예전에는 "unnamed" 였는데, 한글 파일명이
        //   ASCII 슬러그로는 통째로 비므로 **운영 코퍼스 거의 전부가 doc-unnamed-… 로
        //   무너졌다**(PR #527 리뷰 ③) — 로그·감사에서 서로 구별이 안 되고 충돌 면적이
        //   최대가 된다. 슬러그가 없으면 해시만 쓴다: 짧지만 서로 다르다.
        return slug.isEmpty() ? "doc-" + hash : "doc-" + slug + "-" + hash;
    }

    /**
     * DB 에 남길 <b>원래 파일명</b> — 길이를 자르고 제어문자만 지운다.
     *
     * <p>❗<b>{@link #safeFilename} 과 목적이 다르다.</b> 그쪽은 <i>경로 조각</i>이라 괄호·공백
     * 까지 {@code _} 로 접는데, 이 값은 판매자가 받는 파일 이름이라 <b>올린 그대로에 가까워야</b>
     * 한다. 둘을 한 함수로 쓰면 «올린 이름을 보여준다» 가 성립하지 않는다(경로에서 뽑는 것과
     * 같아진다).
     *
     * <p>지우는 것은 <b>제어문자·개행뿐</b>이다 — 그것이 {@code Content-Disposition} 을 갈라
     * 헤더 주입이 되는 자리이고, 비ASCII 는 {@code ContentDisposition.filename(name, UTF_8)}
     * 가 RFC 5987 로 인코딩하므로 살려도 안전하다.
     *
     * <p>❗<b>길이를 여기서 자른다.</b> 예전에는 날것을 그대로 넣었는데
     * {@code original_filename} 이 {@code varchar(255) NOT NULL} 이라, 긴 한글 공시 파일명
     * 하나로 {@code Data too long} → <b>500 + 참조 없는 파일</b>이었다(PR #527 리뷰 ⑤).
     * {@code null} 도 여기서 받는다 — {@code MultipartFile.getOriginalFilename()} 은
     * 문서상 nullable 이라 그대로 넣으면 not-null 위반이다.
     */
    public static String displayFilename(String original) {
        String base = original == null ? "" : HEADER_UNSAFE.matcher(original).replaceAll(" ").trim();
        if (base.length() > DISPLAY_MAX) {
            // 뒤를 남긴다 — 확장자와 회차번호가 뒤에 있어 그쪽이 사람에게 유용하다.
            base = base.substring(base.length() - DISPLAY_MAX);
        }
        return base.isEmpty() ? "document.pdf" : base;
    }

    /**
     * 참조 없는 업로드본을 지운다 — 저장은 됐는데 행이 안 남은 경우 (PR #527 리뷰 ⑦).
     *
     * <p>{@code store()} 가 행 삽입 <b>전에</b> 디스크에 쓴다(ai-service 가 경로를 받으므로
     * 그 순서여야 한다). 그래서 파스가 «문서 문제» 가 아닌 이유로 실패하면 트랜잭션이 굴러
     * 행은 사라지고 <b>파일만 남는다.</b> {@code uploads/} 를 지우는 코드가 레포에 없으므로
     * (보존 정책은 발화만 본다) 참조 없는 바이트가 단조 증가한다.
     *
     * <p>❗<b>실패해도 던지지 않는다.</b> 이건 보상 동작이고, 여기서 던지면 <b>원래 실패
     * 원인이 가려진다</b> — 운영자가 봐야 하는 것은 «파스가 왜 실패했나» 다. 못 지웠으면
     * 로그에 남기고 넘어간다(다음 재업로드가 같은 경로를 덮으므로 새는 것은 용량뿐이다).
     */
    public void deleteQuietly(String relativePath) {
        try {
            Path target = ProductDocuments.resolveWithin(dataDir, relativePath);
            Files.deleteIfExists(target);
            // 디렉토리가 sha256 이라 이 파일 하나뿐이다 — 비었으면 같이 치운다.
            Path parent = target.getParent();
            if (parent != null && !parent.equals(dataDir.toAbsolutePath().normalize())) {
                try (var entries = Files.list(parent)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(parent);
                    }
                }
            }
            log.info("참조 없는 업로드본 정리: path={}", relativePath);
        } catch (IOException | RuntimeException e) {
            log.warn("참조 없는 업로드본을 못 지웠다(용량만 샌다): path={} — {}",
                    relativePath, e.toString());
        }
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
