package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NodeInfoTest {

    @Test
    void constructor_withAllParameters_shouldSetFields() {
        NodeInfo node = new NodeInfo(
                "node123",
                "192.168.1.100",
                "TestNode",
                "password123",
                "Test description",
                "#FF5733",
                "publicKey123"
        );

        assertThat(node.getNodeId()).isEqualTo("node123");
        assertThat(node.getIp()).isEqualTo("192.168.1.100");
        assertThat(node.getName()).isEqualTo("TestNode");
        assertThat(node.getPassword()).isEqualTo("password123");
        assertThat(node.getDescription()).isEqualTo("Test description");
        assertThat(node.getColor()).isEqualTo("#FF5733");
        assertThat(node.getPublicKey()).isEqualTo("publicKey123");
    }

    @Test
    void setters_shouldUpdateFields() {
        NodeInfo node = new NodeInfo(
                "node1",
                "127.0.0.1",
                "Node1",
                "pass1",
                "Desc1",
                "#000000",
                "key1"
        );

        node.setNodeId("node2");
        node.setIp("192.168.1.1");
        node.setName("Node2");
        node.setPassword("pass2");
        node.setDescription("Desc2");
        node.setColor("#FFFFFF");
        node.setPublicKey("key2");

        assertThat(node.getNodeId()).isEqualTo("node2");
        assertThat(node.getIp()).isEqualTo("192.168.1.1");
        assertThat(node.getName()).isEqualTo("Node2");
        assertThat(node.getPassword()).isEqualTo("pass2");
        assertThat(node.getDescription()).isEqualTo("Desc2");
        assertThat(node.getColor()).isEqualTo("#FFFFFF");
        assertThat(node.getPublicKey()).isEqualTo("key2");
    }

    @Test
    void toString_shouldIncludeAllFields() {
        NodeInfo node = new NodeInfo(
                "node456",
                "10.0.0.1",
                "ProductionNode",
                "securePass",
                "Production environment node",
                "#00FF00",
                "pubKey456"
        );

        String result = node.toString();

        assertThat(result).contains("node456");
        assertThat(result).contains("10.0.0.1");
        assertThat(result).contains("ProductionNode");
        assertThat(result).contains("Production environment node");
        assertThat(result).contains("#00FF00");
        assertThat(result).contains("pubKey456");
        assertThat(result).doesNotContain("securePass"); // Password should not be in toString
    }

    @Test
    void toString_shouldHaveCorrectFormat() {
        NodeInfo node = new NodeInfo(
                "id",
                "ip",
                "name",
                "pass",
                "desc",
                "color",
                "key"
        );

        String result = node.toString();

        assertThat(result).startsWith("NodeInfo{");
        assertThat(result).endsWith("}");
        assertThat(result).contains("nodeId='id'");
        assertThat(result).contains("ip='ip'");
        assertThat(result).contains("name='name'");
    }

    @Test
    void constructor_withNullValues_shouldAllowNull() {
        NodeInfo node = new NodeInfo(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(node.getNodeId()).isNull();
        assertThat(node.getIp()).isNull();
        assertThat(node.getName()).isNull();
        assertThat(node.getPassword()).isNull();
        assertThat(node.getDescription()).isNull();
        assertThat(node.getColor()).isNull();
        assertThat(node.getPublicKey()).isNull();
    }

    @Test
    void gettersAndSetters_shouldWorkIndependently() {
        NodeInfo node = new NodeInfo(null, null, null, null, null, null, null);

        node.setNodeId("id1");
        assertThat(node.getNodeId()).isEqualTo("id1");
        
        node.setIp("192.168.0.1");
        assertThat(node.getIp()).isEqualTo("192.168.0.1");
        
        node.setName("TestNode");
        assertThat(node.getName()).isEqualTo("TestNode");
        
        node.setPassword("testPass");
        assertThat(node.getPassword()).isEqualTo("testPass");
        
        node.setDescription("Test Description");
        assertThat(node.getDescription()).isEqualTo("Test Description");
        
        node.setColor("#123456");
        assertThat(node.getColor()).isEqualTo("#123456");
        
        node.setPublicKey("testKey");
        assertThat(node.getPublicKey()).isEqualTo("testKey");
    }
}
