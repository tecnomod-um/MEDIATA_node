package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class NodeHeartbeat {
    private String nodeId;
    private long timestamp;
}
