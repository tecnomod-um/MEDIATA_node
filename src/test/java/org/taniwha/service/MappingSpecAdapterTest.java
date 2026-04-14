package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.taniwha.dto.mapping.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MappingSpecAdapterTest {

    private MappingSpecAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MappingSpecAdapter();
    }

    // -----------------------------------------------------------------------
    // sourceFileName
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("sourceFileName")
    class SourceFileNameTests {

        @Test
        void returnsEmptyString_whenNull() {
            assertThat(adapter.sourceFileName(null)).isEqualTo("");
        }

        @Test
        void returnsWholeString_whenNoSeparator() {
            assertThat(adapter.sourceFileName("myfile.csv")).isEqualTo("myfile.csv");
        }

        @Test
        void returnsPartAfterSeparator_whenSeparatorPresent() {
            assertThat(adapter.sourceFileName("node1::data.csv")).isEqualTo("data.csv");
        }

        @Test
        void returnsPartAfterLastSeparator_whenMultipleSeparators() {
            // Only the first '::' counts as the separator
            assertThat(adapter.sourceFileName("node1::sub::data.csv")).isEqualTo("sub::data.csv");
        }
    }

    // -----------------------------------------------------------------------
    // toLegacyConfigs – null / empty inputs
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("toLegacyConfigs – null/empty guard clauses")
    class NullEmptyGuardTests {

        @Test
        void returnsEmpty_whenSpecIsNull() {
            assertThat(adapter.toLegacyConfigs(null)).isEmpty();
        }

        @Test
        void returnsEmpty_whenMappingsIsNull() {
            MappingSpecDTO spec = new MappingSpecDTO();
            spec.setMappings(null);
            assertThat(adapter.toLegacyConfigs(spec)).isEmpty();
        }

        @Test
        void returnsEmpty_whenMappingsIsEmpty() {
            MappingSpecDTO spec = new MappingSpecDTO();
            spec.setMappings(Collections.emptyList());
            assertThat(adapter.toLegacyConfigs(spec)).isEmpty();
        }

        @Test
        void skipsNullMappingEntries() {
            MappingSpecDTO spec = new MappingSpecDTO();
            spec.setMappings(Collections.singletonList(null));
            assertThat(adapter.toLegacyConfigs(spec)).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // toLegacyConfigs – standard mapping
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("toLegacyConfigs – standard mapping")
    class StandardMappingTests {

        private MappingDefinitionDTO buildStandardMapping(String targetField) {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setTargetField(targetField);
            m.setMappingType("standard");
            return m;
        }

        @Test
        void standardMapping_producesOneEntry_withTargetFieldAsKey() {
            MappingDefinitionDTO m = buildStandardMapping("myField");
            MappingSpecDTO spec = specWith(m);

            List<Map<String, Object>> result = adapter.toLegacyConfigs(spec);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsKey("myField");
        }

        @Test
        void standardMapping_details_containsExpectedKeys() {
            MappingDefinitionDTO m = buildStandardMapping("field1");

            MappingSpecDTO spec = specWith(m);
            List<Map<String, Object>> result = adapter.toLegacyConfigs(spec);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get(0).get("field1");

            assertThat(details).containsKeys("mappingType", "fileName", "columns", "terminology", "description", "groups");
            assertThat(details.get("mappingType")).isEqualTo("standard");
        }

        @Test
        void standardMapping_fileNameFromSourceConfigFile() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            m.setSourceConfigFile("explicit_config.csv");
            MappingInputDTO input = new MappingInputDTO();
            input.setSourceId("nodeA::ignored.csv");
            input.setColumn("col1");
            m.setInputs(List.of(input));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat(details.get("fileName")).isEqualTo("explicit_config.csv");
        }

        @Test
        void standardMapping_fileNameInferredFromInput_whenNoSourceConfigFile() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            m.setSourceConfigFile(null);
            MappingInputDTO input = new MappingInputDTO();
            input.setSourceId("nodeA::mydata.csv");
            input.setColumn("col1");
            m.setInputs(List.of(input));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat(details.get("fileName")).isEqualTo("mydata.csv");
        }

        @Test
        void standardMapping_fileNameFallsBackToCustomMapping_whenNoInputsNoConfigFile() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            m.setSourceConfigFile(null);
            m.setInputs(null);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat(details.get("fileName")).isEqualTo("custom_mapping");
        }

        @Test
        void standardMapping_columnsFromInputs() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            MappingInputDTO i1 = new MappingInputDTO();
            i1.setColumn("col1");
            MappingInputDTO i2 = new MappingInputDTO();
            i2.setColumn("col2");
            m.setInputs(List.of(i1, i2));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat((List<String>) details.get("columns")).containsExactly("col1", "col2");
        }

        @Test
        void standardMapping_columnsDeduplicated() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            MappingInputDTO i1 = new MappingInputDTO();
            i1.setColumn("col1");
            MappingInputDTO i2 = new MappingInputDTO();
            i2.setColumn("col1");
            m.setInputs(List.of(i1, i2));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat((List<String>) details.get("columns")).hasSize(1).containsExactly("col1");
        }

        @Test
        void standardMapping_terminologyAndDescription_fromMetadata() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            MappingMetadataDTO meta = new MappingMetadataDTO();
            meta.setTerminology("ICD-10");
            meta.setDescription("Some desc");
            m.setMetadata(meta);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat(details.get("terminology")).isEqualTo("ICD-10");
            assertThat(details.get("description")).isEqualTo("Some desc");
        }

        @Test
        void standardMapping_terminologyAndDescription_emptyWhenNoMetadata() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            m.setMetadata(null);

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");

            assertThat(details.get("terminology")).isEqualTo("");
            assertThat(details.get("description")).isEqualTo("");
        }

        @Test
        void standardMapping_rulesConvertedToGroups() {
            MappingDefinitionDTO m = buildStandardMapping("target");

            MappingRuleDTO rule = new MappingRuleDTO();
            RuleResultDTO then = new RuleResultDTO();
            then.setKind("fixed");
            then.setValue("YES");
            rule.setThen(then);
            rule.setLogic(Map.of("==", List.of("nodeA::file.csv::col1", "Y")));
            m.setRules(List.of(rule));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("target");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");

            assertThat(groups).hasSize(1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");
            assertThat(values).hasSize(1);
            assertThat(values.get(0).get("name")).isEqualTo("YES");
        }

        @Test
        void standardMapping_sourceValueRule_hasEmptyName() {
            MappingDefinitionDTO m = buildStandardMapping("target");

            MappingRuleDTO rule = new MappingRuleDTO();
            RuleResultDTO then = new RuleResultDTO();
            then.setKind("source-value");
            then.setSourceId("nodeA::file.csv");
            then.setColumn("col1");
            rule.setThen(then);
            rule.setLogic(Map.of("==", List.of("nodeA::file.csv::col1", "Y")));
            m.setRules(List.of(rule));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("target");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

            assertThat(values.get(0).get("name")).isEqualTo("");
        }

        @Test
        void mappingType_caseInsensitive() {
            MappingDefinitionDTO m = buildStandardMapping("f");
            m.setMappingType("STANDARD");

            List<Map<String, Object>> result = adapter.toLegacyConfigs(specWith(m));
            assertThat(result).hasSize(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) result.get(0).get("f");
            assertThat(details.get("mappingType")).isEqualTo("standard");
        }
    }

    // -----------------------------------------------------------------------
    // toLegacyConfigs – one-hot mapping
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("toLegacyConfigs – one-hot mapping")
    class OneHotMappingTests {

        @Test
        void oneHotMapping_noOutputs_producesNoEntries() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");
            m.setOutputs(null);

            assertThat(adapter.toLegacyConfigs(specWith(m))).isEmpty();
        }

        @Test
        void oneHotMapping_oneOutput_producesOneEntry() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO output = new OneHotOutputDTO();
            output.setTargetField("smoker");
            output.setTrueValue("1");
            output.setFalseValue("0");
            m.setOutputs(List.of(output));

            List<Map<String, Object>> result = adapter.toLegacyConfigs(specWith(m));
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsKey("smoker");
        }

        @Test
        void oneHotMapping_details_hasMappingTypeOneHot() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO output = new OneHotOutputDTO();
            output.setTargetField("f");
            m.setOutputs(List.of(output));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");
            assertThat(details.get("mappingType")).isEqualTo("one-hot");
        }

        @Test
        void oneHotMapping_valuesContainTrueAndFalseEntries() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO output = new OneHotOutputDTO();
            output.setTargetField("f");
            output.setTrueValue("Yes");
            output.setFalseValue("No");
            m.setOutputs(List.of(output));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

            assertThat(values).hasSize(2);
            assertThat(values.get(0).get("name")).isEqualTo("Yes");
            assertThat(values.get(1).get("name")).isEqualTo("No");
        }

        @Test
        void oneHotMapping_defaultsTrueValueTo1_whenBlank() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO output = new OneHotOutputDTO();
            output.setTargetField("f");
            output.setTrueValue("");
            output.setFalseValue("");
            m.setOutputs(List.of(output));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

            assertThat(values.get(0).get("name")).isEqualTo("1");
            assertThat(values.get(1).get("name")).isEqualTo("0");
        }

        @Test
        void oneHotMapping_skipsNullOutputs() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");
            m.setOutputs(Collections.singletonList(null));

            assertThat(adapter.toLegacyConfigs(specWith(m))).isEmpty();
        }

        @Test
        void oneHotMapping_multipleOutputs_produceMultipleEntries() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO o1 = new OneHotOutputDTO();
            o1.setTargetField("field_a");
            OneHotOutputDTO o2 = new OneHotOutputDTO();
            o2.setTargetField("field_b");
            m.setOutputs(List.of(o1, o2));

            List<Map<String, Object>> result = adapter.toLegacyConfigs(specWith(m));
            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsKey("field_a");
            assertThat(result.get(1)).containsKey("field_b");
        }

        @Test
        void oneHotMapping_metadataOnOutput() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("one-hot");

            OneHotOutputDTO output = new OneHotOutputDTO();
            output.setTargetField("f");
            MappingMetadataDTO meta = new MappingMetadataDTO();
            meta.setTerminology("SNOMED");
            meta.setDescription("Output desc");
            output.setMetadata(meta);
            m.setOutputs(List.of(output));

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) adapter.toLegacyConfigs(specWith(m)).get(0).get("f");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

            assertThat(values.get(0).get("terminology")).isEqualTo("SNOMED");
            assertThat(values.get(0).get("description")).isEqualTo("Output desc");
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – exact matcher (==)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – exact matcher ==")
    class ExactMatcherTests {

        @Test
        void exactMatch_varRefOnLeft_stringLiteralOnRight() {
            Map<String, Object> logic = Map.of("==", List.of("node::file.csv::col", "someValue"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("fileName", "file.csv")
                    .containsEntry("groupColumn", "col")
                    .containsEntry("value", "someValue");
        }

        @Test
        void exactMatch_literalOnLeft_varRefOnRight() {
            Map<String, Object> logic = Map.of("==", List.of("someValue", "node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "someValue");
        }

        @Test
        void exactMatch_tripleEquals_worksLikeDoubleEquals() {
            Map<String, Object> logic = Map.of("===", List.of("node::file.csv::col", "42"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "42");
        }

        @Test
        void exactMatch_numericLiteral() {
            Map<String, Object> logic = Map.of("==", List.of("node::file.csv::col", 99));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "99");
        }

        @Test
        void exactMatch_varMapRef() {
            Map<String, Object> varRef = Map.of("var", "node::file.csv::col");
            Map<String, Object> logic = Map.of("==", List.of(varRef, "val"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("groupColumn", "col");
        }

        @Test
        void exactMatch_wrongArgCount_returnsEmpty() {
            Map<String, Object> logic = Map.of("==", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).isEmpty();
        }

        @Test
        void exactMatch_sourceIdColumn_mapRef() {
            Map<String, Object> colRef = new LinkedHashMap<>();
            colRef.put("sourceId", "nodeA::data.csv");
            colRef.put("column", "myCol");
            Map<String, Object> logic = Map.of("==", List.of(colRef, "val"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("fileName", "data.csv")
                    .containsEntry("groupColumn", "myCol")
                    .containsEntry("value", "val");
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – in matcher
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – in matcher")
    class InMatcherTests {

        @Test
        void inMatcher_singleElementList_producesExactMatcher() {
            Map<String, Object> logic = Map.of("in", List.of("node::file.csv::col", List.of("theValue")));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "theValue");
        }

        @Test
        void inMatcher_multipleElements_returnsEmpty() {
            Map<String, Object> logic = Map.of("in", List.of("node::file.csv::col", List.of("a", "b")));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).isEmpty();
        }

        @Test
        void inMatcher_emptyList_returnsEmpty() {
            Map<String, Object> logic = Map.of("in", List.of("node::file.csv::col", Collections.emptyList()));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).isEmpty();
        }

        @Test
        void inMatcher_wrongArgCount_returnsEmpty() {
            Map<String, Object> logic = Map.of("in", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – type matcher
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – type matchers")
    class TypeMatcherTests {

        @Test
        void typeMatcher_integer() {
            Map<String, Object> logic = Map.of("type", List.of("node::file.csv::col", "integer"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "integer");
        }

        @Test
        void typeMatcher_double() {
            Map<String, Object> logic = Map.of("taniwha:type", List.of("node::file.csv::col", "double"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "double");
        }

        @Test
        void typeMatcher_date() {
            Map<String, Object> logic = Map.of("type", List.of("node::file.csv::col", "date"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "date");
        }

        @Test
        void typeMatcher_unknownType_returnsEmpty() {
            Map<String, Object> logic = Map.of("type", List.of("node::file.csv::col", "unknown"));
            List<Map<String, Object>> matchers = extractMatchers(logic);

            assertThat(matchers).isEmpty();
        }

        @Test
        void isIntegerOperator() {
            Map<String, Object> logic = Map.of("isInteger", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "integer");
        }

        @Test
        void taniwhaIsIntegerOperator() {
            Map<String, Object> logic = Map.of("taniwha:isInteger", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "integer");
        }

        @Test
        void is_integer_snakeCaseOperator() {
            Map<String, Object> logic = Map.of("is_integer", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "integer");
        }

        @Test
        void isDoubleOperator() {
            Map<String, Object> logic = Map.of("isDouble", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "double");
        }

        @Test
        void isNumberOperator_mapsToDouble() {
            Map<String, Object> logic = Map.of("isNumber", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "double");
        }

        @Test
        void isDateOperator() {
            Map<String, Object> logic = Map.of("isDate", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "date");
        }

        @Test
        void typeMatcher_wrongArgCount_returnsEmpty() {
            Map<String, Object> logic = Map.of("type", List.of("node::file.csv::col"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).isEmpty();
        }

        @Test
        void isIntegerOperator_wrongArgCount_returnsEmpty() {
            Map<String, Object> logic = Map.of("isInteger", List.of("node::file.csv::col", "extra"));
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – OR combinator
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – OR combinator")
    class OrCombinatorTests {

        @Test
        void orWithTwoExactMatches_producesTwoMatchers() {
            Map<String, Object> branch1 = Map.of("==", List.of("n::f.csv::col", "A"));
            Map<String, Object> branch2 = Map.of("==", List.of("n::f.csv::col", "B"));
            Map<String, Object> logic = Map.of("or", List.of(branch1, branch2));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(2);
        }

        @Test
        void orWithSingleBranch() {
            Map<String, Object> branch = Map.of("==", List.of("n::f.csv::col", "X"));
            Map<String, Object> logic = Map.of("or", List.of(branch));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – AND combinator / range
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – AND / range")
    class AndRangeTests {

        @Test
        void andWithRangeBounds_producesRangeMatcher() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col", 10));
            Map<String, Object> upper = Map.of("<=", List.of("n::f.csv::col", 20));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);

            @SuppressWarnings("unchecked")
            Map<String, Object> rangeValue = (Map<String, Object>) matchers.get(0).get("value");
            assertThat(rangeValue).containsEntry("minValue", 10).containsEntry("maxValue", 20);
        }

        @Test
        void andWithRangeBounds_strictOperators() {
            Map<String, Object> lower = Map.of(">", List.of("n::f.csv::col", 5));
            Map<String, Object> upper = Map.of("<", List.of("n::f.csv::col", 15));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);

            @SuppressWarnings("unchecked")
            Map<String, Object> rangeValue = (Map<String, Object>) matchers.get(0).get("value");
            assertThat(rangeValue.get("minValue")).isEqualTo(5);
            assertThat(rangeValue.get("maxValue")).isEqualTo(15);
        }

        @Test
        void andRange_integerValueType_whenBothIntegers() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col", 1));
            Map<String, Object> upper = Map.of("<=", List.of("n::f.csv::col", 5));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);

            @SuppressWarnings("unchecked")
            Map<String, Object> rangeValue = (Map<String, Object>) matchers.get(0).get("value");
            assertThat(rangeValue.get("type")).isEqualTo("integer");
        }

        @Test
        void andRange_doubleValueType_whenDecimals() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col", 1.5));
            Map<String, Object> upper = Map.of("<=", List.of("n::f.csv::col", 9.9));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);

            @SuppressWarnings("unchecked")
            Map<String, Object> rangeValue = (Map<String, Object>) matchers.get(0).get("value");
            assertThat(rangeValue.get("type")).isEqualTo("double");
        }

        @Test
        void andRange_dateValueType_whenDates() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col", "2000-01-01"));
            Map<String, Object> upper = Map.of("<=", List.of("n::f.csv::col", "2020-12-31"));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);

            @SuppressWarnings("unchecked")
            Map<String, Object> rangeValue = (Map<String, Object>) matchers.get(0).get("value");
            assertThat(rangeValue.get("type")).isEqualTo("date");
        }

        @Test
        void andWithMismatchedColumns_returnsEmpty() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col1", 10));
            Map<String, Object> upper = Map.of("<=", List.of("n::f.csv::col2", 20));
            Map<String, Object> logic = Map.of("and", List.of(lower, upper));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).isEmpty();
        }

        @Test
        void andWithNonRangeConditions_fallsBackToIndividualParsing() {
            Map<String, Object> branch1 = Map.of("==", List.of("n::f.csv::col", "A"));
            Map<String, Object> branch2 = Map.of("==", List.of("n::f.csv::col", "B"));
            Map<String, Object> logic = Map.of("and", List.of(branch1, branch2));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            // range parse fails, falls back to individual parsing of each ==
            assertThat(matchers).hasSize(2);
        }

        @Test
        void andWithOnlyOneBound_returnsEmpty() {
            Map<String, Object> lower = Map.of(">=", List.of("n::f.csv::col", 10));
            Map<String, Object> logic = Map.of("and", List.of(lower));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – to_number / to_date / to_string wrappers
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – conversion wrappers")
    class ConversionWrapperTests {

        @Test
        void toNumber_wrappingVarRef_isRecognized() {
            Map<String, Object> varRef = Map.of("var", "n::f.csv::col");
            Map<String, Object> toNumber = Map.of("to_number", List.of(varRef));
            Map<String, Object> logic = Map.of("==", List.of(toNumber, "42"));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("groupColumn", "col");
        }

        @Test
        void toDate_wrappingVarRef_isRecognized() {
            Map<String, Object> varRef = Map.of("var", "n::f.csv::col");
            Map<String, Object> toDate = Map.of("to_date", List.of(varRef));
            Map<String, Object> logic = Map.of("==", List.of(toDate, "2020-01-01"));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
        }

        @Test
        void toNumber_wrappingLiteral_extractsLiteral() {
            Map<String, Object> varRef = Map.of("var", "n::f.csv::col");
            Map<String, Object> toNumber = Map.of("to_number", List.of("77"));
            Map<String, Object> logic = Map.of("==", List.of(varRef, toNumber));

            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).hasSize(1);
            assertThat(matchers.get(0)).containsEntry("value", "77");
        }
    }

    // -----------------------------------------------------------------------
    // Logic parsing – fallback type matcher (source-value with type op)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – source-value fallback type matcher")
    class SourceValueFallbackTests {

        @Test
        void sourceValueResult_withTypeOp_producesFallbackMatcher() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("standard");
            m.setTargetField("t");

            RuleResultDTO then = new RuleResultDTO();
            then.setKind("source-value");
            then.setSourceId("nodeA::data.csv");
            then.setColumn("col1");

            MappingRuleDTO rule = new MappingRuleDTO();
            rule.setThen(then);
            rule.setLogic(Map.of("isInteger", List.of("nodeA::data.csv::col1")));
            m.setRules(List.of(rule));

            List<Map<String, Object>> configs = adapter.toLegacyConfigs(specWith(m));
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) configs.get(0).get("t");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mapping = (List<Map<String, Object>>) values.get(0).get("mapping");

            assertThat(mapping).hasSize(1);
            assertThat(mapping.get(0)).containsEntry("fileName", "data.csv")
                    .containsEntry("groupColumn", "col1")
                    .containsEntry("value", "integer");
        }

        @Test
        void sourceValueResult_withNoTypeOp_producesNoFallbackMatcher() {
            MappingDefinitionDTO m = new MappingDefinitionDTO();
            m.setMappingType("standard");
            m.setTargetField("t");

            RuleResultDTO then = new RuleResultDTO();
            then.setKind("source-value");
            then.setSourceId("nodeA::data.csv");
            then.setColumn("col1");

            MappingRuleDTO rule = new MappingRuleDTO();
            rule.setThen(then);
            rule.setLogic(Map.of("unknownOp", "ignored"));
            m.setRules(List.of(rule));

            List<Map<String, Object>> configs = adapter.toLegacyConfigs(specWith(m));
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) configs.get(0).get("t");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mapping = (List<Map<String, Object>>) values.get(0).get("mapping");

            assertThat(mapping).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases – empty / null logic
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("logic parsing – empty/null logic edge cases")
    class EmptyLogicTests {

        @Test
        void nullLogic_returnsEmptyMatchers() {
            List<Map<String, Object>> matchers = extractMatchers(null);
            assertThat(matchers).isEmpty();
        }

        @Test
        void emptyLogic_returnsEmptyMatchers() {
            List<Map<String, Object>> matchers = extractMatchers(Collections.emptyMap());
            assertThat(matchers).isEmpty();
        }

        @Test
        void unknownOperator_returnsEmpty() {
            Map<String, Object> logic = Map.of("someUnknownOp", "value");
            List<Map<String, Object>> matchers = extractMatchers(logic);
            assertThat(matchers).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // parseSourceColumnRef – var with list value
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("var with list value resolves first element as ref")
    void varWithListValue_resolvesFirstElement() {
        Map<String, Object> varRef = Map.of("var", List.of("n::f.csv::col", "ignored"));
        Map<String, Object> logic = Map.of("==", List.of(varRef, "val"));
        List<Map<String, Object>> matchers = extractMatchers(logic);

        assertThat(matchers).hasSize(1);
        assertThat(matchers.get(0)).containsEntry("groupColumn", "col");
    }

    @Test
    @DisplayName("sourceId separator at position 0 is not a valid ref")
    void sourceIdSeparatorAtPositionZero_isNotValidRef() {
        // "::col" has separator at index 0, which is <= 0, so not valid
        Map<String, Object> logic = Map.of("==", List.of("::col", "val"));
        List<Map<String, Object>> matchers = extractMatchers(logic);
        assertThat(matchers).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Rule metadata
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rule metadata terminology and description are propagated")
    void ruleMetadata_propagated() {
        MappingDefinitionDTO m = new MappingDefinitionDTO();
        m.setMappingType("standard");
        m.setTargetField("t");

        MappingRuleDTO rule = new MappingRuleDTO();
        RuleResultDTO then = new RuleResultDTO();
        then.setKind("fixed");
        then.setValue("X");
        rule.setThen(then);
        rule.setLogic(Map.of("==", List.of("n::f.csv::col", "Y")));
        MappingMetadataDTO meta = new MappingMetadataDTO();
        meta.setTerminology("LOINC");
        meta.setDescription("rule desc");
        rule.setMetadata(meta);
        m.setRules(List.of(rule));

        List<Map<String, Object>> configs = adapter.toLegacyConfigs(specWith(m));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) configs.get(0).get("t");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

        assertThat(values.get(0).get("terminology")).isEqualTo("LOINC");
        assertThat(values.get(0).get("description")).isEqualTo("rule desc");
    }

    @Test
    @DisplayName("null rule is skipped")
    void nullRule_isSkipped() {
        MappingDefinitionDTO m = new MappingDefinitionDTO();
        m.setMappingType("standard");
        m.setTargetField("t");
        m.setRules(Collections.singletonList(null));

        List<Map<String, Object>> configs = adapter.toLegacyConfigs(specWith(m));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) configs.get(0).get("t");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");

        assertThat(values).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MappingSpecDTO specWith(MappingDefinitionDTO... mappings) {
        MappingSpecDTO spec = new MappingSpecDTO();
        spec.setMappings(List.of(mappings));
        return spec;
    }

    /**
     * Helper: build a standard mapping with one rule that has the given logic,
     * then return the extracted legacy matchers for that rule.
     */
    private List<Map<String, Object>> extractMatchers(Map<String, Object> logic) {
        MappingDefinitionDTO m = new MappingDefinitionDTO();
        m.setMappingType("standard");
        m.setTargetField("target");

        MappingRuleDTO rule = new MappingRuleDTO();
        RuleResultDTO then = new RuleResultDTO();
        then.setKind("fixed");
        then.setValue("V");
        rule.setThen(then);
        rule.setLogic(logic);
        m.setRules(List.of(rule));

        List<Map<String, Object>> configs = adapter.toLegacyConfigs(specWith(m));
        if (configs.isEmpty()) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) configs.get(0).get("target");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) details.get("groups");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) groups.get(0).get("values");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mapping = (List<Map<String, Object>>) values.get(0).get("mapping");
        return mapping;
    }
}
