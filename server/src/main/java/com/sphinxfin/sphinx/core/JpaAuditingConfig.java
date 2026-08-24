package com.sphinxfin.sphinx.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** BaseEntity의 @CreatedDate/@LastModifiedDate 자동 채움을 활성화한다. 소유: 강희진 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
