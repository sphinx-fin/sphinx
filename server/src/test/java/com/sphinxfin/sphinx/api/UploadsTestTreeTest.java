package com.sphinxfin.sphinx.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업로드 정리가 <b>마운트 지점을 남기는지</b> 본다. 소유: 강희진
 *
 * <p>실물 {@code data/uploads/} 에 대고 재면 순서 의존이 된다 — 그 트리를 지우는 것이 정리
 * 자신이라, 같은 클래스의 다른 테스트가 먼저 돌면 <b>이미 없는 상태를 재고 초록</b>이 된다.
 * 그게 이 결함이 숨어 있던 방식이라, 여기서는 <b>임시 트리</b>에 대고 잰다.
 */
@DisplayName("업로드 정리 — 마운트 지점과 커밋된 README 는 남는다")
class UploadsTestTreeTest {

    /** 실물과 같은 모양: {@code uploads/README.md} + {@code uploads/<sha256>/<파일>}. */
    private Path tree(Path root) throws IOException {
        Path uploads = Files.createDirectories(root.resolve("uploads"));
        Files.writeString(uploads.resolve("README.md"), "커밋된 것");
        Path content = Files.createDirectories(uploads.resolve("a".repeat(64)));
        Files.writeString(content.resolve("els.pdf"), "업로드 산출물");
        return uploads;
    }

    @Test
    @DisplayName("❗정리가 디렉토리와 README 를 남긴다 — 지우면 ai-service 가 안 뜬다")
    void theMountPointAndTrackedReadmeSurviveCleanup() throws Exception {
        Path root = Files.createTempDirectory("sphinx-uploads-");
        Path uploads = tree(root);

        UploadsTestTree.clearUploads(uploads);

        assertThat(uploads)
                .as("uploads/ 자체가 사라지면 배포에서 runc 가 마운트 지점을 만들려다 "
                        + "부모가 읽기 전용이라 실패하고 ai-service 가 아예 안 뜬다(#532)")
                .exists();
        assertThat(uploads.resolve("README.md"))
                .as("커밋된 파일이다 — 지우면 git status 에 삭제로 뜨고 git add -A 하는 "
                        + "사람이 그것을 커밋한다")
                .exists();
        assertThat(uploads.resolve("a".repeat(64)))
                .as("산출물은 지워야 한다 — 남기면 다른 통합 테스트의 «사전적재 2종» 전제가 깨진다")
                .doesNotExist();
    }

    @Test
    @DisplayName("❗바이트 셈이 README 를 고아로 세지 않는다 — 그것 때문에 단정이 순서에 의존했다")
    void theByteCountIgnoresTheTrackedReadme() throws Exception {
        Path root = Files.createTempDirectory("sphinx-uploads-");
        Path uploads = tree(root);

        assertThat(UploadsTestTree.uploadedFiles(uploads))
                .as("uploads/<sha256>/ 아래만 센다")
                .hasSize(1);

        UploadsTestTree.clearUploads(uploads);

        assertThat(UploadsTestTree.uploadedFiles(uploads))
                .as("정리 뒤에는 0이어야 한다 — README 를 세면 여기서 1이 되고, 그러면 "
                        + "「참조 없는 바이트가 안 남는다」 단정이 그 파일을 신고한다")
                .isEmpty();
    }
}
