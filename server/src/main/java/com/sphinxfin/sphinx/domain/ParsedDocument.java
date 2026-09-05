package com.sphinxfin.sphinx.domain;

import java.util.List;

/**
 * contracts/parsed_document.schema.json 과 1:1 (계약 소유: 정세현, 수요: 윤지석).
 *
 * F-EXT-001(파싱) 출력이자 F-EXT-002(추출) 입력이다. ai-service 는 snake_case 로 말하고
 * (document_id·product_type·parser_version·page_count·char_count·parse_warnings), Java 레코드는
 * camelCase 다 — {@code AiServiceClient} 의 경계 전용 SNAKE_CASE 매퍼가 (역)직렬화를 맡는다.
 * 전역/웹 Jackson 은 camelCase 그대로다.
 *
 * <p>❗<b>이 레코드는 고객 텍스트가 아니라 상품 문서 텍스트를 담는다.</b> 그래서 P3 마스킹
 * 경로를 타지 않는다 — {@code AiServiceClient.parse()}·{@code extract()} 가 {@code score()} 와
 * 달리 {@code PiiGateway.mask()} 를 부르지 않는 근거다.
 *
 * <p><b>source_span 규약</b>(계약 $comment): {@code RiskItem.condition.sourceSpan} 의 start/end 는
 * 해당 페이지의 {@code pages[].text} 에 대한 <b>페이지 상대</b> 오프셋이며 반열린 구간
 * [start, end) 다. 문서 전역 오프셋이 아니다. 따라서 {@code pages[page].text[start:end]} 가
 * {@code condition.valueText} 와 일치해야 하고, F-EXT-002 의 원문 스팬 검증 후처리가 이 등식으로
 * 검사한다. 스팬 검증에는 {@code pageCount} 가 아니라 {@code pages[].charCount} 를 본다.
 */
public record ParsedDocument(
        String documentId,          // 업로드 단위 식별자. productId 와 다르다
        String productType,         // ELS | VARIABLE_INSURANCE (데모 범위 2종)
        String sourceFile,          // 원본 파일명(경로 아님). nullable
        String parserVersion,       // 같은 문서 → 같은 출력 (P2). 출력이 달라지면 올린다
        String parsedAt,            // date-time. nullable
        Integer pageCount,          // 원본 총 페이지 수. 스팬 검증엔 쓰지 말 것. nullable
        List<Page> pages,           // 스팬이 가리키는 대상. 표 안 텍스트도 읽기 순서대로 포함
        List<Table> tables,         // 구조화 부가 뷰. 없는 텍스트는 없다(pages[].text 가 전량)
        List<ParseWarning> parseWarnings  // 파싱 실패는 은폐하지 않고 노출. 빈 배열이면 완전 성공
) {
    /**
     * 한 페이지. text 는 유니코드 NFC 정규화이며 공백·개행을 접지 않는다 — 접으면 스팬
     * 오프셋이 무의미해진다. charCount 는 코드포인트 기준(코드유닛 아님)이며 nullable.
     */
    public record Page(int page, String text, Integer charCount) {}

    /** ELS 기초자산·배리어 표처럼 행열 구조가 의미를 갖는 경우의 부가 뷰. caption nullable. */
    public record Table(int page, String caption, List<List<String>> rows) {}

    /**
     * 파싱 경고. code ∈ {TABLE_STRUCTURE_LOST, TEXT_LAYER_MISSING, ENCODING_SUSPECT,
     * MANUAL_OVERRIDE}. MANUAL_OVERRIDE 는 파서가 아니라 사람이 만든 JSON 이라는 표시다.
     * page 는 nullable.
     */
    public record ParseWarning(Integer page, String code, String message) {}
}
