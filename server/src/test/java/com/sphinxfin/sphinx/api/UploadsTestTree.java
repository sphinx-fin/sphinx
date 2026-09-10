package com.sphinxfin.sphinx.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 테스트가 {@code data/uploads/} 를 정리하는 방법. 소유: 강희진
 *
 * <h2>❗디렉토리와 그 안의 추적 파일은 남긴다</h2>
 *
 * <p>업로드 테스트는 산출물을 지워야 한다 — 남기면 다른 통합 테스트의 <i>"GET /products 는
 * 사전적재 2종"</i> 전제가 깨진다. 그런데 <b>{@code data/uploads/} 자체와 그 안의
 * {@code README.md} 는 커밋된 것</b>이다({@code #532}).
 *
 * <p>예전에는 {@code Files.walk(uploadsDir)} 를 역순으로 전부 지웠다. 그래서 <b>테스트를
 * 한 번 돌리면 추적 파일이 사라지고 디렉토리도 없어졌다</b>(실측: {@code git status} 가
 * {@code D data/uploads/README.md}). 결과가 둘이다.
 *
 * <ol>
 *   <li>{@code git add -A} 하는 사람이 <b>그 삭제를 커밋한다.</b></li>
 *   <li>그 상태가 배포에 가면 <b>ai-service 가 아예 안 뜬다</b> — {@code ./data:/data:ro} 위에
 *       {@code /data/uploads} 를 겹쳐 붙일 때 runc 가 마운트 지점을 만들려다 부모가 읽기
 *       전용이라 실패한다({@code data/uploads/README.md} 가 그 실측을 든다). 배포 스크립트의
 *       {@code [ -d data/uploads ]} 가드는 <i>"레포를 통째로 clone 했는지 확인한다"</i> 로
 *       엉뚱한 원인을 가리킨다.</li>
 * </ol>
 *
 * <p>❗그리고 <b>단정 쪽에도 같은 착시가 있었다.</b> <i>"참조 없는 바이트가 안 남는다"</i> 를
 * 재는 단정이 {@code uploads/} 아래 <b>모든</b> 정규 파일을 셌으므로, 커밋된
 * {@code README.md} 를 <b>고아 바이트로 센다</b>. 그 단정은 <b>같은 클래스의 다른 테스트가
 * 먼저 그 파일을 지워 준 덕에</b> 초록이었다 — 혼자 돌리면 빨갛다(실측).
 *
 * <p>그래서 「업로드 산출물」의 정의를 한 곳에 둔다: <b>{@code uploads/<sha256>/} 아래</b> 다.
 */
final class UploadsTestTree {

    private UploadsTestTree() {
    }

    /**
     * 업로드 산출물 정리 — {@code uploads/} 의 <b>하위 디렉토리만</b> 지운다.
     *
     * <p>뿌리와 그 아래 정규 파일({@code README.md})은 손대지 않는다. 정리 실패는 테스트
     * 결과가 아니므로 조용히 넘긴다 — 다음 실행이 덮어쓴다.
     */
    static void clearUploads(Path uploadsDir) throws IOException {
        if (!Files.isDirectory(uploadsDir)) {
            return;
        }
        List<Path> contentDirs = new ArrayList<>();
        try (Stream<Path> children = Files.list(uploadsDir)) {
            children.filter(Files::isDirectory).forEach(contentDirs::add);
        }
        for (Path dir : contentDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 정리 실패는 테스트 결과가 아니다.
                    }
                });
            }
        }
    }

    /**
     * 업로드된 바이트 — {@code uploads/<sha256>/} 아래 정규 파일만.
     *
     * <p>뿌리에 커밋돼 있는 {@code README.md} 는 산출물이 아니므로 빼고 센다.
     */
    static List<Path> uploadedFiles(Path uploadsDir) throws IOException {
        if (!Files.isDirectory(uploadsDir)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(uploadsDir)) {
            List<Path> dirs = children.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(out::add);
                }
            }
        }
        return out;
    }
}
