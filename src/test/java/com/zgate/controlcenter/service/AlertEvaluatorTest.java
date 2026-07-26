package com.zgate.controlcenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlertEvaluatorTest {

    @Test
    void comparesSymbolAndNamedOperators() {
        assertThat(AlertEvaluator.compare(5, ">", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(5, "GT", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(3, ">=", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(2, "<", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(3, "LTE", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(3, "==", 3.0)).isTrue();
        assertThat(AlertEvaluator.compare(5, "!=", 3.0)).isTrue();
    }

    @Test
    void falseWhenNotBreachedOrMalformed() {
        assertThat(AlertEvaluator.compare(2, ">", 3.0)).isFalse();
        assertThat(AlertEvaluator.compare(2, null, 3.0)).isFalse();
        assertThat(AlertEvaluator.compare(2, ">", null)).isFalse();
        assertThat(AlertEvaluator.compare(2, "bogus", 3.0)).isFalse();
    }
}
