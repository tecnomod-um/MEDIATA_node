package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NodeProvenanceBatch {
    private String nodeId;
    private int schemaVersion = 1;
    private List<NodeProvenanceActivity> activities = new ArrayList<>();
}
