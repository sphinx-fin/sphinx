package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장 실패 문면이 <b>원인을 가리킨다</b>. 소유: 강희진 (이슈 #560)
 *
 * <h2>왜 문면을 테스트하나 — 뭉친 문면이 원문 예외보다 나빴다</h2>
 *
 * <p>예전에는 모든 {@code IOException} 을 <i>"uploads 볼륨이 쓰기로 붙었는지 확인하라"</i>
 * 로 냈고, 주석이 <i>"여기서 실패하는 것은 마운트·권한 문제다"</i> 로 단정했다.
 * <b>{@code #557} 에서 그 단정이 거짓이었다</b> — 실제 원인은 파일명이 352 바이트였고
 * (ext4 한계 255), 그 문면을 받은 사람은 볼륨 마운트를 뒤졌다. 아무것도 안 고쳐진다.
 *
 * <p>이 자리에 올 수 있는 것이 넷이고 고칠 자리가 전부 다르다 — {@code EACCES} ·
 * {@code ENOSPC}({@code #554}) · {@code ENAMETOOLONG}({@code #558}) · {@code EROFS}.
 * 그중 옛 문면이 맞는 것은 하나뿐이었다.
 *
 * <p>❗<b>문면을 재는 테스트는 대개 값이 낮은데 여기는 다르다.</b> 이 문면이 운영자의
 * 다음 행동을 정하고, 틀린 문면은 <b>틀린 곳을 가리키므로</b> 아무 문면도 없는 것보다
 * 나쁘다. 그게 {@code #557} 의 대가였다.
 */
@DisplayName("업로드 저장 실패 문면 (이슈 #560)")
class UploadStoreFailureWordingTest {

    private static final byte[] PDF = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("❗권한 실패만 「볼륨이 쓰기로 붙었는지」를 말한다 — 소유자까지 짚는다")
    void anAccessDenialNamesTheVolumeAndTheOwner(@TempDir Path dataDir) throws IOException {
        Path uploads = Files.createDirectory(dataDir.resolve("uploads"));
        // 쓰기 권한을 뺀다. 그러면 하위 디렉토리 생성이 AccessDeniedException 이다.
        uploads.toFile().setWritable(false);
        UploadedDocumentStore store = new UploadedDocumentStore(dataDir.toString());

        try {
            assertThatThrownBy(() -> store.store("x.pdf", PDF))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("권한이 없다")
                    // 결정 7.54 가 uid 를 맞추는 주체다 — 볼륨은 붙었는데 소유자가 root 인 상태.
                    .hasMessageContaining("uid 10001")
                    // ❗ro 마운트 안내를 여기 두면 도달 불가능한 문면이 된다(위 테스트 참조).
                    .hasMessageNotContaining("쓰기로 붙었는지");
        } finally {
            uploads.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("❗권한이 아닌 실패는 원인을 문면에 싣는다 — 어느 갈래로 가든")
    void aNonPermissionFailureCarriesItsCause(@TempDir Path dataDir) throws IOException {
        // uploads 를 **파일**로 만들어 둔다 — 디렉토리 생성이 실패하는데 권한 문제가 아니다.
        Files.write(dataDir.resolve("uploads"), PDF);
        UploadedDocumentStore store = new UploadedDocumentStore(dataDir.toString());

        UncheckedIOException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> store.store("x.pdf", PDF), UncheckedIOException.class);

        assertThat(thrown).isNotNull();
        // 옛 문면이 새 자리에 남아 있으면 안 된다.
        assertThat(thrown).hasMessageNotContaining("쓰기로 붙었는지");

        // ❗**기대값을 던져진 원인에서 만든다**(PR #561 리뷰). 문면을 하드코딩하면
        //   플랫폼에 갈린다 — macOS 는 "Not a directory" 인데 리눅스에서는
        //   FileAlreadyExistsException(reason null)일 수 있고, 그러면 CI 에서만 빨개진다
        //   (#558 이 방금 다룬 그 부류다). 원인 쪽에서 만들면 어느 갈래로 가든 같은 답이다.
        //
        // 그리고 이 단정이 «원인이 하나도 없는 문면» 을 막는다 — 예전에는
        // hasMessageNotContaining 하나뿐이라 통째로 뭉친 문면도 초록이었다.
        IOException cause = thrown.getCause();
        String expected = cause instanceof FileSystemException fse && fse.getReason() != null
                ? fse.getReason() : cause.toString();
        assertThat(thrown)
                .as("우리가 원인을 지어내면 그 추측이 틀리는 날이 온다(#557) — 원인을 그대로 싣는다")
                .hasMessageContaining(expected);
    }

    @Test
    @DisplayName("★ getReason() 갈래를 직접 잰다 — #557 을 고치는 갈래가 이것인데 테스트가 없었다")
    void theOsReasonBranchIsMeasuredDirectly() {
        // ENOSPC 를 테스트에서 만들 수는 없지만 예외를 만들어 넣을 수는 있다. 그러면
        // 플랫폼에 갈리는 하드코딩도 안 생긴다(PR #561 리뷰).
        // ❗#554(상한·정리 없음)가 실제로 나는 날 이 문면이 맞게 나가는 것을 미리 보장한다.
        UncheckedIOException thrown = UploadedDocumentStore.storeFailed(
                Path.of("/data/uploads/abc/x.pdf"),
                new FileSystemException("/data/uploads/abc/x.pdf", null,
                        "No space left on device"));

        // ❗**문면 전체를 본다 — `hasMessageContaining(reason)` 은 이 갈래를 안 갈랐다**
        //   (이슈 #587). 분기를 죽이면 마지막 갈래로 떨어지는데 거기가 원문 예외를 `+ e` 로
        //   싣고, `FileSystemException.toString()` 이 reason 을 이미 담는다. 그래서 세 단정이
        //   폴백에서도 전부 참이었다 — 실측: 분기에 `false &&` 를 붙여도 BUILD SUCCESSFUL.
        //
        //   갈래를 가르는 것은 **괄호**다: 이 갈래는 사유만 괄호에 담고 원문 예외를 안 싣는다.
        assertThat(thrown)
                .as("디스크가 찬 것을 「볼륨이 쓰기로 붙었는지」로 안내하면 볼륨은 정상이므로 "
                        + "확인해도 아무 문제가 안 보인다. 그리고 원문 예외를 통째로 싣는 "
                        + "폴백 문면이면 사유가 그 안에 묻힌다")
                .hasMessage(reasonWording("No space left on device", "/data/uploads/abc/x.pdf"));
    }

    /**
     * {@code getReason()} 갈래의 문면 — {@code UploadedDocumentStore.storeFailed} 와 짝이다.
     *
     * <p>❗<b>형식을 여기 한 벌 둔다.</b> 이 파일이 재는 것이 «문면» 이라 형식이 곧 단정이고,
     * 그래서 형식이 바뀌면 <b>빨개지는 것이 맞다</b>. 두 테스트가 같은 문자열을 따로 적으면
     * 한쪽만 고쳐지는 자리가 생긴다.
     */
    private static String reasonWording(String reason, String path) {
        return "업로드 문서를 저장하지 못했다(" + reason + "): " + path;
    }

    @Test
    @DisplayName("★ 폴백은 원문 예외를 통째로 싣는다 — 추측하지 않는 것이 이 갈래의 값이다")
    void theFallbackCarriesTheRawException() {
        // ❗**이 갈래도 그물이 없었다**(PR #592 리뷰 실측). 폴백에서 `+ e` 를 떼도 전체
        //   스위트가 초록이었다 — 그러면 reason 이 null 인 실패에서 운영자가 받는 것이
        //   경로 하나가 되고 원인이 통째로 없어진다(#557 이 겪은 그 상태).
        //
        //   왜 안 잡혔나: 「어느 갈래로 가든」을 재는 위 테스트가 기대값을 던져진 원인에서
        //   만든다(플랫폼 갈림을 피하는 좋은 설계다). 그 대가로 **어느 갈래가 도는지가
        //   플랫폼에 달린다** — macOS 는 "Not a directory"(reason 있음)라 getReason 갈래로
        //   가고, 폴백에는 아무도 안 닿았다. 즉 폴백을 재는 것이 「CI 가 리눅스라서」에
        //   기대고 있었다.
        //
        //   그래서 ★ 테스트들과 같은 방식으로 storeFailed 를 직접 부른다 — 플랫폼과 무관하다.
        IOException cause = new IOException("boom");   // reason 이 없고 권한도 아니다
        UncheckedIOException thrown = UploadedDocumentStore.storeFailed(
                Path.of("/data/uploads/abc/x.pdf"), cause);

        assertThat(thrown)
                .as("원인을 지어내지 않는 대신 원문을 그대로 싣는 것이 이 갈래의 전부다 — "
                        + "그것까지 빠지면 「저장하지 못했다」와 경로만 남는다")
                .hasMessage("업로드 문서를 저장하지 못했다: /data/uploads/abc/x.pdf — " + cause);
    }

    @Test
    @DisplayName("❗ro 마운트는 첫째 갈래가 아니다 — 그 문면이 도달 불가능한 안내였다")
    void aReadOnlyMountGoesToTheReasonBranch() {
        // 실측(JDK 21 · docker `-v …:ro`): FileSystemException / reason="Read-only file system".
        // AccessDeniedException 이 아니다 — 그래서 첫 문면의 「쓰기로 붙었는지」는 이 상황에
        // 닿을 수 없었다(PR #561 리뷰).
        UncheckedIOException thrown = UploadedDocumentStore.storeFailed(
                Path.of("/data/uploads/abc/x.pdf"),
                new FileSystemException("/data/uploads/abc/x.pdf", null,
                        "Read-only file system"));

        // 이쪽도 문면 전체를 본다(이슈 #587) — 폴백도 「Read-only file system」을 담으므로
        // `Containing` 만으로는 «둘째 갈래로 갔다» 를 증명하지 못한다.
        assertThat(thrown)
                .hasMessage(reasonWording("Read-only file system", "/data/uploads/abc/x.pdf"));
    }

    @Test
    @DisplayName("정상 경로는 그대로 — 문면을 가르면서 저장이 바뀌면 안 된다")
    void theHappyPathIsUnchanged(@TempDir Path dataDir) throws IOException {
        UploadedDocumentStore store = new UploadedDocumentStore(dataDir.toString());

        UploadedDocumentStore.Stored stored = store.store("els_4181.pdf", PDF);

        assertThat(stored.documentPath()).matches("uploads/[0-9a-f]{64}/els_4181\\.pdf");
        assertThat(Files.readAllBytes(dataDir.resolve(stored.documentPath()))).isEqualTo(PDF);
    }
}
