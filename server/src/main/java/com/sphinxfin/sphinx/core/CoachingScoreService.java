package com.sphinxfin.sphinx.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * F-DET-002 코칭 스코어 계산. 소유: 강희진
 *
 * 취약 요인(연령·가입금액대·투자경험·채널)을 선언적 vulnerability_weights.yaml로 가중해
 * '코칭 스코어'를 낸다. 모순(F-DET-002 판정=ai-service)이 있으면 가산한다.
 *
 * 이 점수는 게이트 신호가 아니다(모순 자체는 게이트 R-02가 처리). 코칭 스코어는 세션 메타로
 * 저장돼 F-INT-004 맞춤 재설명(고령자 모드 등)·F-GTE-004 리포트에 쓰인다.
 */
@Service
public class CoachingScoreService {

    private final List<Factor> factors;
    private final int mismatchBonus;
    private final int vulnerableThreshold;

    public CoachingScoreService() {
        this("/vulnerability_weights.yaml");
    }

    CoachingScoreService(String classpathResource) {
        Weights w = load(classpathResource);
        this.factors = w.factors;
        this.mismatchBonus = w.mismatchBonus;
        this.vulnerableThreshold = w.vulnerableThreshold;
    }

    /** 세션 속성 + 모순 여부로 코칭 스코어와 취약 여부를 계산한다. */
    public Result score(Session session, boolean suitabilityMismatch) {
        Map<String, String> attrs = attributes(session);
        int total = 0;
        for (Factor f : factors) {
            String value = attrs.get(f.field);
            if (value != null && f.weights != null) {
                total += f.weights.getOrDefault(value, 0);
            }
        }
        if (suitabilityMismatch) {
            total += mismatchBonus;
        }
        return new Result(total, total >= vulnerableThreshold);
    }

    private static Map<String, String> attributes(Session s) {
        return Map.of(
                "ageBand", nullToEmpty(s.ageBand()),
                "amountBand", nullToEmpty(s.amountBand()),
                "experienceLevel", nullToEmpty(s.experienceLevel()),
                "channel", s.channel() == null ? "" : s.channel().name());
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static Weights load(String classpathResource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = CoachingScoreService.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("취약 가중치 파일을 찾을 수 없다: " + classpathResource);
            }
            return yaml.readValue(in, Weights.class);
        } catch (IOException e) {
            throw new UncheckedIOException("취약 가중치 로드 실패: " + classpathResource, e);
        }
    }

    /** 코칭 스코어 산출 결과. */
    public record Result(int score, boolean vulnerable) {}

    /** vulnerability_weights.yaml 역직렬화 형태. */
    private static final class Weights {
        public int version;
        public List<Factor> factors;
        @JsonProperty("mismatch-bonus")
        public int mismatchBonus;
        @JsonProperty("vulnerable-threshold")
        public int vulnerableThreshold;
    }

    private static final class Factor {
        public String field;
        public Map<String, Integer> weights;
    }
}
