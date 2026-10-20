package org.taniwha.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class NodeProvenanceBatchResponse {
    private List<String> acknowledgedEventIds = new ArrayList<>();
}
