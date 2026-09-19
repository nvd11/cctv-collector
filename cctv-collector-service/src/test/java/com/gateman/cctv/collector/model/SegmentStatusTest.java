package com.gateman.cctv.collector.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentStatusTest {

    @Test
    @DisplayName("Should contain exactly four lifecycle states")
    void shouldContainExpectedLifecycleStates() {
        assertThat(SegmentStatus.values()).containsExactly(
                SegmentStatus.WRITING,
                SegmentStatus.CLOSED,
                SegmentStatus.COMMITTED,
                SegmentStatus.CORRUPTED
        );
    }

    @Test
    @DisplayName("Should only allow CLOSED status to be ready for cloud upload")
    void shouldOnlyAllowClosedForUpload() {
        assertThat(SegmentStatus.WRITING.isReadyForUpload()).isFalse();
        assertThat(SegmentStatus.CLOSED.isReadyForUpload()).isTrue();
        assertThat(SegmentStatus.COMMITTED.isReadyForUpload()).isFalse();
        assertThat(SegmentStatus.CORRUPTED.isReadyForUpload()).isFalse();
    }

    @Test
    @DisplayName("Should properly identify terminal states")
    void shouldIdentifyTerminalStates() {
        assertThat(SegmentStatus.WRITING.isTerminal()).isFalse();
        assertThat(SegmentStatus.CLOSED.isTerminal()).isFalse();
        assertThat(SegmentStatus.COMMITTED.isTerminal()).isTrue();
        assertThat(SegmentStatus.CORRUPTED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(SegmentStatus.class)
    @DisplayName("All statuses must have a non-blank description")
    void shouldHaveNonBlankDescription(SegmentStatus status) {
        assertThat(status.getDescription()).isNotBlank();
    }
}
