package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * F-DET-002 코칭 스코어 계산. 소유: 강희진
 *
 * 취약 요인(연령·가입금액대·투자경험·채널)을 vulnerability_weights.yaml로 가중해 코칭
 * 스코어를 낸다. 모순(판정=ai-service)이 있으면 가산. 이 점수는 게이트 신호가 아니라
 * 세션 메타로, F-INT-004 맞춤 재설명·F-GTE-004 리포트에 쓴다.
 */
@Service
public class CoachingScoreService {

    /** 세션 속성 → (값 → 가중치). */
    private final Map<String, Map<String, Integer>> weights;
    private final int mismatchBonus;
    private final int vulnerableThreshold;

    public CoachingScoreService() {
        Config config = load("/vulnerability_weights.yaml");
        this.weights = config.factors();
        this.mismatchBonus = config.mismatchBonus();
        this.vulnerableThreshold = config.vulnerableThreshold();
    }

    /** 세션 속성 + 모순 여부로 코칭 스코어·취약 여부를 계산한다. */
    public Result score(Session session, boolean suitabilityMismatch) {
        int total =
                  weightOf("ageBand", session.ageBand())
                + weightOf("amountBand", session.amountBand())
                + weightOf("experienceLevel", session.experienceLevel())
                + weightOf("channel", session.channel() == null ? null : session.channel().name());
        if (suitabilityMismatch) {
            total += mismatchBonus;
        }
        return new Result(total, total >= vulnerableThreshold);
    }

    /** 한 속성의 가중치. 매핑에 없는 값·null은 0점. */
    private int weightOf(String field, String value) {
        if (value == null) {
            return 0;
        }
        return weights.getOrDefault(field, Map.of()).getOrDefault(value, 0);
    }

    private static Config load(String classpathResource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = CoachingScoreService.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("취약 가중치 파일을 찾을 수 없다: " + classpathResource);
            }
            return yaml.readValue(in, Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException("취약 가중치 로드 실패: " + classpathResource, e);
        }
    }

    /** 코칭 스코어 산출 결과. */
    public record Result(int score, boolean vulnerable) {}

    /** vulnerability_weights.yaml 역직렬화 형태. */
    private record Config(
            Map<String, Map<String, Integer>> factors,
            @JsonProperty("mismatch-bonus") int mismatchBonus,
            @JsonProperty("vulnerable-threshold") int vulnerableThreshold) {}
}
