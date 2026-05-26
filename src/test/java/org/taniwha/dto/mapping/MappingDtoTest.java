package org.taniwha.dto.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingDtoTest {

    // DatasetBindingDTO

    @Test
    void datasetBindingDTO_gettersAndSetters() {
        DatasetBindingDTO dto = new DatasetBindingDTO();
        dto.setSourceId("src1");
        dto.setNodeId("node1");
        dto.setElementFileName("elements.csv");
        dto.setDatasets(List.of("ds1", "ds2"));

        assertThat(dto.getSourceId()).isEqualTo("src1");
        assertThat(dto.getNodeId()).isEqualTo("node1");
        assertThat(dto.getElementFileName()).isEqualTo("elements.csv");
        assertThat(dto.getDatasets()).containsExactly("ds1", "ds2");
    }

    // MappingCaseDTO

    @Test
    void mappingCaseDTO_gettersAndSetters() {
        MappingCaseDTO dto = new MappingCaseDTO();
        dto.setId("case-1");
        dto.setWhen(Map.of("==", List.of("src::file.csv::col", "value")));
        dto.setOutputValue("result");
        dto.setOutputValueFrom("src::file.csv::col");

        MappingMetadataDTO meta = new MappingMetadataDTO();
        meta.setTerminology("ICD-10");
        meta.setDescription("A case");
        dto.setMetadata(meta);

        assertThat(dto.getId()).isEqualTo("case-1");
        assertThat(dto.getWhen()).containsKey("==");
        assertThat(dto.getOutputValue()).isEqualTo("result");
        assertThat(dto.getOutputValueFrom()).isEqualTo("src::file.csv::col");
        assertThat(dto.getMetadata().getTerminology()).isEqualTo("ICD-10");
        assertThat(dto.getMetadata().getDescription()).isEqualTo("A case");
    }

    // MappingDefinitionDTO

    @Test
    void mappingDefinitionDTO_gettersAndSetters() {
        MappingDefinitionDTO dto = new MappingDefinitionDTO();
        dto.setId("def-1");
        dto.setTargetField("myField");
        dto.setGroupName("group1");
        dto.setMappingType("standard");

        MappingInputDTO input = new MappingInputDTO();
        input.setSourceId("src::file.csv");
        input.setColumn("col1");
        dto.setInputs(List.of(input));

        MappingRuleDTO rule = new MappingRuleDTO();
        dto.setRules(List.of(rule));

        OneHotOutputDTO output = new OneHotOutputDTO();
        dto.setOutputs(List.of(output));

        MappingMetadataDTO meta = new MappingMetadataDTO();
        dto.setMetadata(meta);

        dto.setSourceConfigFile("config.csv");
        dto.setRemoveSourceColumns(true);

        assertThat(dto.getId()).isEqualTo("def-1");
        assertThat(dto.getTargetField()).isEqualTo("myField");
        assertThat(dto.getGroupName()).isEqualTo("group1");
        assertThat(dto.getMappingType()).isEqualTo("standard");
        assertThat(dto.getInputs()).hasSize(1);
        assertThat(dto.getRules()).hasSize(1);
        assertThat(dto.getOutputs()).hasSize(1);
        assertThat(dto.getMetadata()).isSameAs(meta);
        assertThat(dto.getSourceConfigFile()).isEqualTo("config.csv");
        assertThat(dto.isRemoveSourceColumns()).isTrue();
    }

    // MappingInputDTO

    @Test
    void mappingInputDTO_gettersAndSetters() {
        MappingInputDTO dto = new MappingInputDTO();
        dto.setSourceId("node1::file.csv");
        dto.setColumn("columnA");

        assertThat(dto.getSourceId()).isEqualTo("node1::file.csv");
        assertThat(dto.getColumn()).isEqualTo("columnA");
    }

    // MappingMatcherDTO

    @Test
    void mappingMatcherDTO_gettersAndSetters() {
        MappingMatcherDTO dto = new MappingMatcherDTO();
        dto.setSourceId("node1::file.csv");
        dto.setColumn("col");
        dto.setMatchType("exact");
        dto.setValue("testValue");
        dto.setValueType("string");
        dto.setMinValue(0);
        dto.setMaxValue(100);

        assertThat(dto.getSourceId()).isEqualTo("node1::file.csv");
        assertThat(dto.getColumn()).isEqualTo("col");
        assertThat(dto.getMatchType()).isEqualTo("exact");
        assertThat(dto.getValue()).isEqualTo("testValue");
        assertThat(dto.getValueType()).isEqualTo("string");
        assertThat(dto.getMinValue()).isEqualTo(0);
        assertThat(dto.getMaxValue()).isEqualTo(100);
    }

    // MappingMetadataDTO

    @Test
    void mappingMetadataDTO_gettersAndSetters() {
        MappingMetadataDTO dto = new MappingMetadataDTO();
        dto.setTerminology("SNOMED-CT");
        dto.setDescription("Test description");

        assertThat(dto.getTerminology()).isEqualTo("SNOMED-CT");
        assertThat(dto.getDescription()).isEqualTo("Test description");
    }

    // MappingRuleDTO

    @Test
    void mappingRuleDTO_gettersAndSetters() {
        MappingRuleDTO dto = new MappingRuleDTO();
        dto.setId("rule-1");

        Map<String, Object> logic = Map.of("==", List.of("a", "b"));
        dto.setLogic(logic);

        RuleResultDTO result = new RuleResultDTO();
        result.setKind("fixed");
        result.setValue("YES");
        dto.setThen(result);

        MappingMetadataDTO meta = new MappingMetadataDTO();
        meta.setTerminology("ICD");
        dto.setMetadata(meta);

        assertThat(dto.getId()).isEqualTo("rule-1");
        assertThat(dto.getLogic()).isSameAs(logic);
        assertThat(dto.getThen().getKind()).isEqualTo("fixed");
        assertThat(dto.getThen().getValue()).isEqualTo("YES");
        assertThat(dto.getMetadata().getTerminology()).isEqualTo("ICD");
    }

    // MappingSourceDTO

    @Test
    void mappingSourceDTO_gettersAndSetters() {
        MappingSourceDTO dto = new MappingSourceDTO();
        dto.setSourceId("nodeA::dataset.csv");
        dto.setNodeId("nodeA");
        dto.setFileName("dataset.csv");

        assertThat(dto.getSourceId()).isEqualTo("nodeA::dataset.csv");
        assertThat(dto.getNodeId()).isEqualTo("nodeA");
        assertThat(dto.getFileName()).isEqualTo("dataset.csv");
    }

    // MappingSpecDTO

    @Test
    void mappingSpecDTO_gettersAndSetters() {
        MappingSpecDTO dto = new MappingSpecDTO();
        dto.setSpecVersion("1.0");
        dto.setRuleLanguage("json-logic");
        dto.setTargetSchema(Map.of("type", "object"));

        MappingSourceDTO src = new MappingSourceDTO();
        dto.setSources(List.of(src));

        DatasetBindingDTO binding = new DatasetBindingDTO();
        dto.setDatasetBindings(List.of(binding));

        MappingDefinitionDTO def = new MappingDefinitionDTO();
        dto.setMappings(List.of(def));

        assertThat(dto.getSpecVersion()).isEqualTo("1.0");
        assertThat(dto.getRuleLanguage()).isEqualTo("json-logic");
        assertThat(dto.getTargetSchema()).containsEntry("type", "object");
        assertThat(dto.getSources()).hasSize(1);
        assertThat(dto.getDatasetBindings()).hasSize(1);
        assertThat(dto.getMappings()).hasSize(1);
    }

    // OneHotOutputDTO

    @Test
    void oneHotOutputDTO_gettersAndSetters() {
        OneHotOutputDTO dto = new OneHotOutputDTO();
        dto.setTargetField("smoker");
        dto.setLogic(Map.of("isInteger", List.of("n::f.csv::col")));
        dto.setTrueValue("1");
        dto.setFalseValue("0");

        MappingMetadataDTO meta = new MappingMetadataDTO();
        meta.setTerminology("LOCAL");
        dto.setMetadata(meta);

        assertThat(dto.getTargetField()).isEqualTo("smoker");
        assertThat(dto.getLogic()).containsKey("isInteger");
        assertThat(dto.getTrueValue()).isEqualTo("1");
        assertThat(dto.getFalseValue()).isEqualTo("0");
        assertThat(dto.getMetadata().getTerminology()).isEqualTo("LOCAL");
    }

    // RuleResultDTO

    @Test
    void ruleResultDTO_gettersAndSetters() {
        RuleResultDTO dto = new RuleResultDTO();
        dto.setKind("source-value");
        dto.setValue("someValue");
        dto.setSourceId("nodeA::data.csv");
        dto.setColumn("col1");

        assertThat(dto.getKind()).isEqualTo("source-value");
        assertThat(dto.getValue()).isEqualTo("someValue");
        assertThat(dto.getSourceId()).isEqualTo("nodeA::data.csv");
        assertThat(dto.getColumn()).isEqualTo("col1");
    }
}
