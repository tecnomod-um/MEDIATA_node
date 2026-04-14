package org.taniwha.service;

import org.springframework.stereotype.Service;
import org.taniwha.dto.mapping.*;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MappingSpecAdapter {

    private static final Set<String> TYPE_MARKERS = Set.of("integer", "double", "date");
    private static final Object UNSET = new Object();

    public String sourceFileName(String sourceId) {
        if (sourceId == null) return "";
        int sep = sourceId.indexOf("::");
        return sep >= 0 ? sourceId.substring(sep + 2) : sourceId;
    }

    public List<Map<String, Object>> toLegacyConfigs(MappingSpecDTO spec) {
        if (spec == null || spec.getMappings() == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> out = new ArrayList<>();

        for (MappingDefinitionDTO mapping : spec.getMappings()) {
            if (mapping == null) continue;

            String type = safe(mapping.getMappingType()).toLowerCase(Locale.ROOT);

            if ("one-hot".equals(type)) {
                out.addAll(convertOneHot(mapping));
            } else {
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put(resolveStandardKey(mapping), convertStandardDetails(mapping));
                out.add(obj);
            }
        }

        return out;
    }

    private String resolveStandardKey(MappingDefinitionDTO mapping) {
        return safe(mapping.getTargetField());
    }

    private List<Map<String, Object>> convertOneHot(MappingDefinitionDTO mapping) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mapping.getOutputs() == null) return out;

        for (OneHotOutputDTO output : mapping.getOutputs()) {
            if (output == null) continue;

            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put(
                    safe(output.getTargetField()),
                    convertOneHotDetails(mapping, output)
            );
            out.add(obj);
        }

        return out;
    }

    private Map<String, Object> convertStandardDetails(MappingDefinitionDTO mapping) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mappingType", "standard");
        details.put("fileName", resolveStandardFileName(mapping));
        details.put("columns", toColumnList(mapping.getInputs()));
        details.put("terminology", mapping.getMetadata() != null ? safe(mapping.getMetadata().getTerminology()) : "");
        details.put("description", mapping.getMetadata() != null ? safe(mapping.getMetadata().getDescription()) : "");
        details.put("groups", List.of(
                Map.of(
                        "column", safe(mapping.getTargetField()),
                        "values", convertRules(mapping.getRules())
                )
        ));
        return details;
    }

    private String resolveStandardFileName(MappingDefinitionDTO mapping) {
        String explicit = safe(mapping.getSourceConfigFile());
        if (!explicit.isBlank()) return explicit;

        if (mapping.getInputs() != null && !mapping.getInputs().isEmpty()) {
            String sourceId = mapping.getInputs().get(0).getSourceId();
            String inferred = sourceFileName(sourceId);
            if (!safe(inferred).isBlank()) return inferred;
        }

        return "custom_mapping";
    }

    private Map<String, Object> convertOneHotDetails(MappingDefinitionDTO mapping, OneHotOutputDTO output) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mappingType", "one-hot");
        details.put("fileName", "custom_mapping");
        details.put("columns", toColumnList(mapping.getInputs()));
        details.put("terminology", mapping.getMetadata() != null ? safe(mapping.getMetadata().getTerminology()) : "");
        details.put("description", mapping.getMetadata() != null ? safe(mapping.getMetadata().getDescription()) : "");

        List<Map<String, Object>> values = new ArrayList<>();

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("name", safe(output.getTrueValue()).isBlank() ? "1" : safe(output.getTrueValue()));
        one.put("mapping", convertLogicToLegacyMatchers(output.getLogic(), null));
        one.put("terminology", output.getMetadata() != null ? safe(output.getMetadata().getTerminology()) : "");
        one.put("description", output.getMetadata() != null ? safe(output.getMetadata().getDescription()) : "");
        values.add(one);

        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("name", safe(output.getFalseValue()).isBlank() ? "0" : safe(output.getFalseValue()));
        zero.put("mapping", Collections.emptyList());
        zero.put("terminology", "");
        zero.put("description", "");
        values.add(zero);

        details.put("groups", List.of(
                Map.of(
                        "column", safe(output.getTargetField()),
                        "values", values
                )
        ));

        return details;
    }

    private List<Map<String, Object>> convertRules(List<MappingRuleDTO> rules) {
        if (rules == null) return Collections.emptyList();

        List<Map<String, Object>> values = new ArrayList<>();

        for (MappingRuleDTO rule : rules) {
            if (rule == null) continue;

            Map<String, Object> value = new LinkedHashMap<>();

            RuleResultDTO result = rule.getThen();
            String name = isSourceValueResult(result) ? "" : safe(result != null ? result.getValue() : null);

            value.put("name", name);
            value.put("mapping", convertLogicToLegacyMatchers(rule.getLogic(), result));
            value.put("terminology", rule.getMetadata() != null ? safe(rule.getMetadata().getTerminology()) : "");
            value.put("description", rule.getMetadata() != null ? safe(rule.getMetadata().getDescription()) : "");

            values.add(value);
        }

        return values;
    }

    private boolean isSourceValueResult(RuleResultDTO result) {
        return result != null && "source-value".equalsIgnoreCase(safe(result.getKind()));
    }

    private List<Map<String, Object>> convertLogicToLegacyMatchers(Map<String, Object> logic, RuleResultDTO result) {
        if (logic == null || logic.isEmpty()) {
            return Collections.emptyList();
        }

        List<LegacyMatcher> parsed = parseMatchersFromLogic(logic);

        if (parsed.isEmpty() && isSourceValueResult(result)) {
            LegacyMatcher fallback = tryBuildFallbackTypeMatcher(logic, result);
            if (fallback != null) {
                parsed = List.of(fallback);
            }
        }

        return parsed.stream()
                .map(this::toLegacyMap)
                .collect(Collectors.toList());
    }

    private LegacyMatcher tryBuildFallbackTypeMatcher(Map<String, Object> logic, RuleResultDTO result) {
        String inferredType = detectDeclaredType(logic);
        if (inferredType == null || result == null) return null;
        if (safe(result.getSourceId()).isBlank() || safe(result.getColumn()).isBlank()) return null;

        return new LegacyMatcher(
                sourceFileName(result.getSourceId()),
                result.getColumn(),
                inferredType
        );
    }

    private List<LegacyMatcher> parseMatchersFromLogic(Object logicObj) {
        if (!(logicObj instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
            return Collections.emptyList();
        }

        Map.Entry<?, ?> entry = rawMap.entrySet().iterator().next();
        String op = String.valueOf(entry.getKey());
        Object argsObj = entry.getValue();

        if ("or".equals(op)) {
            List<Object> args = asList(argsObj);
            List<LegacyMatcher> out = new ArrayList<>();
            for (Object arg : args) {
                out.addAll(parseMatchersFromLogic(arg));
            }
            return out;
        }

        if ("and".equals(op)) {
            LegacyMatcher range = tryParseRangeMatcher(argsObj);
            if (range != null) {
                return List.of(range);
            }

            List<Object> args = asList(argsObj);
            List<LegacyMatcher> out = new ArrayList<>();
            for (Object arg : args) {
                out.addAll(parseMatchersFromLogic(arg));
            }
            return out;
        }

        LegacyMatcher exact = tryParseExactMatcher(op, argsObj);
        if (exact != null) return List.of(exact);

        LegacyMatcher type = tryParseTypeMatcher(op, argsObj);
        if (type != null) return List.of(type);

        LegacyMatcher inMatcher = tryParseInMatcher(op, argsObj);
        if (inMatcher != null) return List.of(inMatcher);

        return Collections.emptyList();
    }

    private LegacyMatcher tryParseExactMatcher(String op, Object argsObj) {
        if (!"==".equals(op) && !"===".equals(op)) {
            return null;
        }

        List<Object> args = asList(argsObj);
        if (args.size() != 2) return null;

        SourceColumnRef leftRef = parseSourceColumnRef(args.get(0));
        Object leftLiteral = parseLiteral(args.get(1));

        if (leftRef != null && leftLiteral != UNSET) {
            return new LegacyMatcher(leftRef.fileName(), leftRef.column(), normalizeExactLiteral(leftLiteral));
        }

        SourceColumnRef rightRef = parseSourceColumnRef(args.get(1));
        Object rightLiteral = parseLiteral(args.get(0));

        if (rightRef != null && rightLiteral != UNSET) {
            return new LegacyMatcher(rightRef.fileName(), rightRef.column(), normalizeExactLiteral(rightLiteral));
        }

        return null;
    }

    private LegacyMatcher tryParseInMatcher(String op, Object argsObj) {
        if (!"in".equals(op)) {
            return null;
        }

        List<Object> args = asList(argsObj);
        if (args.size() != 2) return null;

        SourceColumnRef ref = parseSourceColumnRef(args.get(0));
        if (ref == null) return null;

        Object haystack = args.get(1);
        if (!(haystack instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        if (list.size() != 1) {
            return null;
        }

        Object value = parseLiteral(list.get(0));
        if (value == UNSET) return null;

        return new LegacyMatcher(ref.fileName(), ref.column(), normalizeExactLiteral(value));
    }

    private LegacyMatcher tryParseTypeMatcher(String op, Object argsObj) {
        List<Object> args = asList(argsObj);

        if ("type".equals(op) || "taniwha:type".equals(op)) {
            if (args.size() != 2) return null;

            SourceColumnRef ref = parseSourceColumnRef(args.get(0));
            String declaredType = normalizeDeclaredType(args.get(1));
            if (ref == null || declaredType == null) return null;

            return new LegacyMatcher(ref.fileName(), ref.column(), declaredType);
        }

        if ("isInteger".equals(op) || "taniwha:isInteger".equals(op) || "is_integer".equals(op)) {
            if (args.size() != 1) return null;
            SourceColumnRef ref = parseSourceColumnRef(args.get(0));
            return ref == null ? null : new LegacyMatcher(ref.fileName(), ref.column(), "integer");
        }

        if ("isDouble".equals(op) || "taniwha:isDouble".equals(op) || "is_double".equals(op)) {
            if (args.size() != 1) return null;
            SourceColumnRef ref = parseSourceColumnRef(args.get(0));
            return ref == null ? null : new LegacyMatcher(ref.fileName(), ref.column(), "double");
        }

        if ("isNumber".equals(op) || "taniwha:isNumber".equals(op) || "is_number".equals(op)) {
            if (args.size() != 1) return null;
            SourceColumnRef ref = parseSourceColumnRef(args.get(0));
            return ref == null ? null : new LegacyMatcher(ref.fileName(), ref.column(), "double");
        }

        if ("isDate".equals(op) || "taniwha:isDate".equals(op) || "is_date".equals(op)) {
            if (args.size() != 1) return null;
            SourceColumnRef ref = parseSourceColumnRef(args.get(0));
            return ref == null ? null : new LegacyMatcher(ref.fileName(), ref.column(), "date");
        }

        return null;
    }

    private LegacyMatcher tryParseRangeMatcher(Object argsObj) {
        List<Object> args = asList(argsObj);
        if (args.size() < 2) return null;

        RangeAccumulator acc = new RangeAccumulator();

        for (Object arg : args) {
            if (!(arg instanceof Map<?, ?> raw) || raw.isEmpty()) {
                return null;
            }

            Map.Entry<?, ?> e = raw.entrySet().iterator().next();
            String op = String.valueOf(e.getKey());
            List<Object> opArgs = asList(e.getValue());
            if (opArgs.size() != 2) {
                return null;
            }

            SourceColumnRef ref = parseSourceColumnRef(opArgs.get(0));
            Object lit = parseLiteral(opArgs.get(1));

            if (ref == null || lit == UNSET) {
                ref = parseSourceColumnRef(opArgs.get(1));
                lit = parseLiteral(opArgs.get(0));
            }

            if (ref == null || lit == UNSET) return null;

            acc.accept(ref, op, lit);
            if (!acc.valid) return null;
        }

        if (!acc.valid || acc.ref == null || acc.min == UNSET || acc.max == UNSET) {
            return null;
        }

        String valueType = inferRangeValueType(acc.min, acc.max);

        Map<String, Object> rangeValue = new LinkedHashMap<>();
        rangeValue.put("type", valueType);
        rangeValue.put("minValue", acc.min);
        rangeValue.put("maxValue", acc.max);

        return new LegacyMatcher(acc.ref.fileName(), acc.ref.column(), rangeValue);
    }

    private Map<String, Object> toLegacyMap(LegacyMatcher matcher) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileName", matcher.fileName());
        map.put("groupColumn", matcher.groupColumn());
        map.put("value", matcher.value());
        return map;
    }

    private SourceColumnRef parseSourceColumnRef(Object obj) {
        if (obj == null) return null;

        if (obj instanceof Map<?, ?> raw) {
            if (raw.containsKey("var")) {
                Object varValue = raw.get("var");
                if (varValue instanceof List<?> list && !list.isEmpty()) {
                    return parseSourceColumnRef(list.get(0));
                }
                return parseSourceColumnRef(varValue);
            }

            if (raw.size() == 1) {
                Map.Entry<?, ?> entry = raw.entrySet().iterator().next();
                String op = String.valueOf(entry.getKey());

                if ("to_number".equals(op) || "to_date".equals(op) || "to_string".equals(op)) {
                    List<Object> args = asList(entry.getValue());
                    if (!args.isEmpty()) {
                        return parseSourceColumnRef(args.get(0));
                    }
                }
            }

            Object sourceId = raw.get("sourceId");
            Object column = raw.get("column");
            if (sourceId != null && column != null) {
                return new SourceColumnRef(
                        String.valueOf(sourceId),
                        sourceFileName(String.valueOf(sourceId)),
                        String.valueOf(column)
                );
            }

            return null;
        }

        if (!(obj instanceof String s)) return null;

        String text = s.trim();
        if (text.isEmpty()) return null;

        int lastSep = text.lastIndexOf("::");
        if (lastSep <= 0 || lastSep >= text.length() - 2) {
            return null;
        }

        String sourceId = text.substring(0, lastSep);
        String column = text.substring(lastSep + 2);

        return new SourceColumnRef(sourceId, sourceFileName(sourceId), column);
    }

    private Object parseLiteral(Object obj) {
        if (obj == null) return null;

        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return obj;
        }

        if (obj instanceof Map<?, ?> raw) {
            if (raw.containsKey("var")) {
                return UNSET;
            }

            if (raw.containsKey("value")) {
                return parseLiteral(raw.get("value"));
            }

            if (raw.size() == 1) {
                Map.Entry<?, ?> entry = raw.entrySet().iterator().next();
                String op = String.valueOf(entry.getKey());

                if ("to_number".equals(op) || "to_date".equals(op) || "to_string".equals(op)) {
                    List<Object> args = asList(entry.getValue());
                    if (!args.isEmpty()) {
                        return parseLiteral(args.get(0));
                    }
                }
            }

            return UNSET;
        }

        return UNSET;
    }

    private String normalizeExactLiteral(Object value) {
        if (value == null) return null;
        return String.valueOf(value);
    }

    private String normalizeDeclaredType(Object value) {
        if (value == null) return null;
        String t = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return TYPE_MARKERS.contains(t) ? t : null;
    }

    private String detectDeclaredType(Object logicObj) {
        if (!(logicObj instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return null;
        }

        Map.Entry<?, ?> entry = raw.entrySet().iterator().next();
        String op = String.valueOf(entry.getKey());
        List<Object> args = asList(entry.getValue());

        if ("type".equals(op) || "taniwha:type".equals(op)) {
            if (args.size() == 2) {
                return normalizeDeclaredType(args.get(1));
            }
        }

        if ("isInteger".equals(op) || "taniwha:isInteger".equals(op) || "is_integer".equals(op)) return "integer";
        if ("isDouble".equals(op) || "taniwha:isDouble".equals(op) || "is_double".equals(op)) return "double";
        if ("isNumber".equals(op) || "taniwha:isNumber".equals(op) || "is_number".equals(op)) return "double";
        if ("isDate".equals(op) || "taniwha:isDate".equals(op) || "is_date".equals(op)) return "date";

        return null;
    }

    private String inferRangeValueType(Object min, Object max) {
        if (isDateLike(min) && isDateLike(max)) {
            return "date";
        }

        if (isIntegerLike(min) && isIntegerLike(max)) {
            return "integer";
        }

        if (isNumericLike(min) && isNumericLike(max)) {
            return "double";
        }

        return "double";
    }

    private boolean isDateLike(Object value) {
        if (value == null) return false;
        return org.taniwha.util.DateUtil.parseDate(String.valueOf(value)).isPresent();
    }

    private boolean isIntegerLike(Object value) {
        if (value == null) return false;

        try {
            double d = org.taniwha.util.NumberUtil.parseDouble(String.valueOf(value));
            return d == Math.rint(d);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isNumericLike(Object value) {
        if (value == null) return false;

        try {
            org.taniwha.util.NumberUtil.parseDouble(String.valueOf(value));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> toColumnList(List<MappingInputDTO> inputs) {
        if (inputs == null) return Collections.emptyList();

        return inputs.stream()
                .filter(Objects::nonNull)
                .map(MappingInputDTO::getColumn)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Object> asList(Object obj) {
        if (obj instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return obj == null ? Collections.emptyList() : List.of(obj);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private record SourceColumnRef(String sourceId, String fileName, String column) {}

    private record LegacyMatcher(String fileName, String groupColumn, Object value) {}

    private static final class RangeAccumulator {
        private SourceColumnRef ref;
        private Object min = UNSET;
        private Object max = UNSET;
        private boolean valid = true;

        void accept(SourceColumnRef nextRef, String op, Object literal) {
            if (!valid) return;

            if (ref == null) {
                ref = nextRef;
            } else if (!Objects.equals(ref.sourceId(), nextRef.sourceId())
                    || !Objects.equals(ref.column(), nextRef.column())) {
                valid = false;
                return;
            }

            switch (op) {
                case ">=", ">" -> min = literal;
                case "<=", "<" -> max = literal;
                default -> valid = false;
            }
        }
    }
}