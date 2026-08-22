package com.sphinxfin.sphinx.core;

/**
 * ai-service(FastAPI) 호출 클라이언트. 소유: 강희진 (엔드포인트 스펙: 윤지석과 협의)
 * 규칙: 고객 텍스트는 PiiGateway.mask() 통과분만 전달. base-url은 application.yml.
 * 엔드포인트: /internal/parse, /internal/extract, /internal/question, /internal/score,
 *            /internal/misconception, /internal/reexplain
 */
public class AiServiceClient {
    // TODO(강희진): RestClient 기반 구현
}
