package com.sphinxfin.sphinx.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 이해항목 <b>표시명</b>. 소유: 정세현 (집계 축) · 강희진 (계약)
 *
 * <p>ID 원문(<code>ELS-TOTAL-LOSS-SCENARIO</code>)이 심사위원 화면과 <b>교부 문서</b>에
 * 그대로 뜨던 것을 없앤다(이슈 #346). 두 자리가 같은 결함인데 고치는 층이 하나다 —
 * <b>화면에 표를 두는 안이 기각된 이유가 그것</b>이다(결정 10.59 · 10.76): web 표는 종이를
 * 안 고치고, web 에는 러너가 없어 표가 갈려도 아무 말이 없다.
 *
 * <h2>❗이름은 판정이 아니다 — 없으면 ID 로 되돌아간다</h2>
 *
 * <p>{@link #itemName}·{@link #productName} 은 모르는 키에 {@code null} 을 준다. 예외를
 * 던지지 않는 것이 설계다: 표시명이 없다고 집계나 교부를 멈추면 <b>라벨 하나 때문에 측정과
 * 기록이 죽는다.</b> 카탈로그에 없는 항목이 들어오는 경로가 실제로 있다 — 루브릭이 늘고
 * 이 사본이 안 따라온 순간이고, 그때 화면은 지금까지 그랬듯 ID 를 그린다. 어긋남 자체는
 * {@code RiskItemCatalogMirrorsRubricsTest} 가 CI 에서 잡는다.
 *
 * <h2>왜 사본을 들고 있나</h2>
 *
 * <p>정본은 {@code ai-service/app/rubrics/*.yaml} 인데 <b>server 이미지에서 그 파일을 읽을
 * 방법이 없다</b> — 빌드 컨텍스트가 {@code ./server} 하나다. 자세한 근거는
 * {@code risk_item_catalog.yaml} 머리말에 적혀 있다.
 */
@Service
public class RiskItemCatalog {

    private static final String RESOURCE = "/risk_item_catalog.yaml";

    private final Map<String, Entry> items;
    private final Map<String, String> productTypes;

    public RiskItemCatalog() {
        Config config = load(RESOURCE);
        this.items = Map.copyOf(config.items());
        this.productTypes = Map.copyOf(config.productTypes());
    }

    /**
     * 이해항목 표시명. 모르는 {@code itemId} 면 {@code null}.
     *
     * <p>회차 값이 안 붙은 <b>채점 기준 쪽</b> 이름이다(결정 5.42) — 집계는 상품을 넘어
     * 합치는 축이라 {@code 낙인 배리어 45%} 를 쓰면 같은 항목이 두 줄이 된다.
     */
    public String itemName(String itemId) {
        Entry entry = items.get(itemId);
        return entry == null ? null : entry.name();
    }

    /**
     * 상품 <b>유형</b>의 표시명. 유형이 아닌 키(실제 상품ID)면 {@code null}.
     *
     * <p>히트맵 상품 축에는 합성 세션의 유형({@code ELS}·{@code VARIABLE_INSURANCE})과 실제
     * 상품ID({@code doc-els-kiwoom-4181})가 <b>둘 다 올 수 있다.</b> 후자의 이름은
     * {@code GET /products} 가 이미 주므로 여기서 비우고 화면이 그쪽으로 그린다 — 두 벌이
     * 되지 않게 <b>겹치는 자리를 만들지 않는 것</b>이 이 {@code null} 의 뜻이다.
     */
    public String productName(String productTypeOrId) {
        return productTypes.get(productTypeOrId);
    }

    /** 항목ID → 표시명 전량. 대조 테스트가 읽는다. */
    public Map<String, String> itemNames() {
        Map<String, String> flat = new LinkedHashMap<>();
        items.forEach((id, entry) -> flat.put(id, entry.name()));
        return Map.copyOf(flat);
    }

    /** 항목ID → 상품유형 전량. 대조 테스트가 읽는다. */
    public Map<String, String> itemProductTypes() {
        Map<String, String> flat = new LinkedHashMap<>();
        items.forEach((id, entry) -> flat.put(id, entry.productType()));
        return Map.copyOf(flat);
    }

    /** 상품유형 → 표시명 전량. */
    public Map<String, String> productNames() {
        return productTypes;
    }

    private static Config load(String classpathResource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = RiskItemCatalog.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                // ❗여기서만 던진다. 파일이 통째로 없는 것은 개별 항목이 없는 것과 다르다 —
                //   전자는 이미지가 잘못 빌드된 것이고, 조용히 빈 표로 뜨면 화면이 이슈 #346
                //   이전 상태로 정확히 되돌아가 아무도 못 알아챈다.
                throw new IllegalStateException("이해항목 카탈로그를 찾을 수 없다: " + classpathResource);
            }
            return yaml.readValue(in, Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException("이해항목 카탈로그 로드 실패: " + classpathResource, e);
        }
    }

    /** risk_item_catalog.yaml 의 한 항목. */
    private record Entry(@JsonProperty("product-type") String productType, String name) {}

    /** risk_item_catalog.yaml 역직렬화 형태. */
    private record Config(
            Map<String, Entry> items,
            @JsonProperty("product-types") Map<String, String> productTypes) {}
}
