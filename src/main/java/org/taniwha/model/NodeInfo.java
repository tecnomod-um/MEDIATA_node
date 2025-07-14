package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class NodeInfo {

    private String nodeId;
    private String ip;
    private String name;
    private String password;
    private String description;
    private String color;
    private String publicKey;

    @Override
    public String toString() {
        return "NodeInfo{" +
                "nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", color='" + color + '\'' +
                ", publicKey='" + publicKey + '\'' +
                '}';
    }
}
