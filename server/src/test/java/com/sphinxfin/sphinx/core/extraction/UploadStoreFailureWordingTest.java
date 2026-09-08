package com.sphinxfin.sphinx.core.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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
                    // #532 에서 찾은 것이 정확히 이 자리다 — 볼륨은 붙었는데 소유자가 root 다.
                    .hasMessageContaining("uid 10001");
        } finally {
            uploads.toFile().setWritable(true);
        }
    }

    @Test
    @DisplayName("❗원인을 모르면 추측하지 않는다 — 원문 예외를 그대로 보여준다")
    void anUnknownFailureShowsTheOriginalException(@TempDir Path dataDir) {
        // uploads 를 **파일**로 만들어 둔다 — 디렉토리 생성이 실패하는데 권한 문제가 아니다
        // (리눅스는 NotDirectory/FileAlreadyExists 계열, macOS 도 권한이 아니다).
        UploadedDocumentStore store = new UploadedDocumentStore(dataDir.toString());
        assertThatThrownBy(() -> {
            Files.write(dataDir.resolve("uploads"), PDF);
            store.store("x.pdf", PDF);
        })
                .isInstanceOf(UncheckedIOException.class)
                .as("우리가 원인을 지어내면 그 추측이 틀리는 날이 온다(#557)")
                // 옛 문면이 새 자리에 남아 있으면 안 된다 — 그게 이 이슈의 요점이다.
                .hasMessageNotContaining("쓰기로 붙었는지");
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
