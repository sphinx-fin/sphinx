package com.sphinxfin.sphinx.domain;

/** F-INT-001: CREATED → IN_PROGRESS → (RE_EXPLAIN ⇄ RE_VERIFY)* → JUDGED → CLOSED/ABORTED */
public enum SessionState { CREATED, IN_PROGRESS, RE_EXPLAIN, RE_VERIFY, JUDGED, CLOSED, ABORTED }
