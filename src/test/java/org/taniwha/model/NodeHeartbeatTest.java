package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

class NodeHeartbeatTest {

    @Test
    void constructor_shouldInitializeAllFields() {
        long currentTime = System.currentTimeMillis();
        NodeHeartbeat heartbeat = new NodeHeartbeat("node-123", currentTime);

        assertThat(heartbeat.getNodeId()).isEqualTo("node-123");
        assertThat(heartbeat.getTimestamp()).isEqualTo(currentTime);
    }

    @Test
    void setters_shouldUpdateFields() {
        NodeHeartbeat heartbeat = new NodeHeartbeat("initial-id", 1000L);

        heartbeat.setNodeId("updated-id");
        heartbeat.setTimestamp(2000L);

        assertThat(heartbeat.getNodeId()).isEqualTo("updated-id");
        assertThat(heartbeat.getTimestamp()).isEqualTo(2000L);
    }

    @Test
    void timestamp_shouldHandleVariousValues() {
        long past = 1609459200000L; // 2021-01-01 00:00:00 UTC
        long now = System.currentTimeMillis();
        long future = now + 86400000L; // 24 hours from now

        NodeHeartbeat pastBeat = new NodeHeartbeat("node1", past);
        NodeHeartbeat nowBeat = new NodeHeartbeat("node2", now);
        NodeHeartbeat futureBeat = new NodeHeartbeat("node3", future);

        assertThat(pastBeat.getTimestamp()).isLessThan(now);
        assertThat(nowBeat.getTimestamp()).isCloseTo(now, withinPercentage(1));
        assertThat(futureBeat.getTimestamp()).isGreaterThan(now);
    }

    @Test
    void nodeId_shouldHandleEmptyAndNullValues() {
        NodeHeartbeat emptyId = new NodeHeartbeat("", 1000L);
        NodeHeartbeat nullId = new NodeHeartbeat(null, 1000L);

        assertThat(emptyId.getNodeId()).isEmpty();
        assertThat(nullId.getNodeId()).isNull();
    }

    @Test
    void nodeId_specialCharacters_shouldBePreserved() {
        String specialId = "node-with-UUID:123e4567-e89b-12d3-a456-426614174000";
        NodeHeartbeat heartbeat = new NodeHeartbeat(specialId, 1000L);

        assertThat(heartbeat.getNodeId()).isEqualTo(specialId);
    }

    @Test
    void timestamp_zeroAndNegative_shouldBeAllowed() {
        NodeHeartbeat zeroTime = new NodeHeartbeat("node", 0L);
        NodeHeartbeat negativeTime = new NodeHeartbeat("node", -1L);

        assertThat(zeroTime.getTimestamp()).isZero();
        assertThat(negativeTime.getTimestamp()).isNegative();
    }

    @Test
    void multipleHeartbeats_differentNodes_shouldBeIndependent() {
        long time1 = System.currentTimeMillis();
        long time2 = time1 + 1000;

        NodeHeartbeat hb1 = new NodeHeartbeat("node-A", time1);
        NodeHeartbeat hb2 = new NodeHeartbeat("node-B", time2);

        assertThat(hb1.getNodeId()).isNotEqualTo(hb2.getNodeId());
        assertThat(hb1.getTimestamp()).isNotEqualTo(hb2.getTimestamp());
    }
}
