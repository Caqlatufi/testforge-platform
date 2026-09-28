package io.testforge.casecatalog.testcase.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class ParameterSchemaValidator {

    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null"
    );

    void validateSchema(Map<String, Object> schema) {
        if (schema == null) {
            throw new TestCaseValidationException("parameters 参数 Schema 不能为空");
        }
        validateSchemaNode(schema, "parameters");
        Object rootType = schema.get("type");
        if (rootType != null && !allowsType(rootType, "object")) {
            throw new TestCaseValidationException("parameters 根 Schema 必须允许 object 类型");
        }
    }

    void validateParameters(Map<String, Object> schema, Map<String, Object> parameters) {
        if (parameters == null) {
            throw new TestCaseValidationException("用例参数不能为空");
        }
        validateValue(schema, parameters, "parameters");
    }

    private void validateSchemaNode(Map<String, Object> schema, String path) {
        validateTypeKeyword(schema.get("type"), path + ".type");

        Object properties = schema.get("properties");
        if (properties != null) {
            Map<?, ?> propertyMap = requireMap(properties, path + ".properties");
            propertyMap.forEach((name, nestedSchema) -> {
                if (!(name instanceof String propertyName) || propertyName.isBlank()) {
                    throw new TestCaseValidationException(path + ".properties 的属性名必须是非空字符串");
                }
                validateSchemaNode(requireStringObjectMap(nestedSchema, path + ".properties." + propertyName),
                        path + ".properties." + propertyName);
            });
        }

        Object required = schema.get("required");
        if (required != null) {
            List<?> names = requireList(required, path + ".required");
            Set<String> uniqueNames = new HashSet<>();
            for (Object name : names) {
                if (!(name instanceof String propertyName) || propertyName.isBlank()) {
                    throw new TestCaseValidationException(path + ".required 只能包含非空属性名");
                }
                if (!uniqueNames.add(propertyName)) {
                    throw new TestCaseValidationException(path + ".required 不能包含重复属性: " + propertyName);
                }
            }
        }

        Object additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null && !(additionalProperties instanceof Boolean)) {
            validateSchemaNode(
                    requireStringObjectMap(additionalProperties, path + ".additionalProperties"),
                    path + ".additionalProperties"
            );
        }

        Object items = schema.get("items");
        if (items != null) {
            validateSchemaNode(requireStringObjectMap(items, path + ".items"), path + ".items");
        }

        Object enumValues = schema.get("enum");
        if (enumValues != null) {
            List<?> values = requireList(enumValues, path + ".enum");
            if (values.isEmpty()) {
                throw new TestCaseValidationException(path + ".enum 不能为空数组");
            }
            if (new HashSet<>(values).size() != values.size()) {
                throw new TestCaseValidationException(path + ".enum 不能包含重复值");
            }
        }

        validateNonNegativeInteger(schema.get("minLength"), path + ".minLength");
        validateNonNegativeInteger(schema.get("maxLength"), path + ".maxLength");
        validateNonNegativeInteger(schema.get("minItems"), path + ".minItems");
        validateNonNegativeInteger(schema.get("maxItems"), path + ".maxItems");
        validateOrderedBounds(schema, "minLength", "maxLength", path);
        validateOrderedBounds(schema, "minItems", "maxItems", path);
        validateNumberKeyword(schema.get("minimum"), path + ".minimum");
        validateNumberKeyword(schema.get("maximum"), path + ".maximum");
        validateOrderedBounds(schema, "minimum", "maximum", path);

        Object pattern = schema.get("pattern");
        if (pattern != null) {
            if (!(pattern instanceof String regex)) {
                throw new TestCaseValidationException(path + ".pattern 必须是字符串");
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException exception) {
                throw new TestCaseValidationException(path + ".pattern 不是合法正则表达式");
            }
        }

        if (schema.containsKey("default")) {
            validateValue(schema, schema.get("default"), path + ".default");
        }
    }

    private void validateTypeKeyword(Object type, String path) {
        if (type == null) {
            return;
        }
        if (type instanceof String singleType) {
            requireKnownType(singleType, path);
            return;
        }
        List<?> types = requireList(type, path);
        if (types.isEmpty()) {
            throw new TestCaseValidationException(path + " 不能为空数组");
        }
        Set<String> unique = new HashSet<>();
        for (Object item : types) {
            if (!(item instanceof String itemType)) {
                throw new TestCaseValidationException(path + " 只能包含字符串类型名");
            }
            requireKnownType(itemType, path);
            if (!unique.add(itemType)) {
                throw new TestCaseValidationException(path + " 不能包含重复类型: " + itemType);
            }
        }
    }

    private void requireKnownType(String type, String path) {
        if (!TYPES.contains(type)) {
            throw new TestCaseValidationException(path + " 包含不支持的 JSON Schema 类型: " + type);
        }
    }

    private void validateValue(Map<String, Object> schema, Object value, String path) {
        Object type = schema.get("type");
        if (type != null && !matchesAnyType(type, value)) {
            throw new TestCaseValidationException(path + " 不符合声明的类型 " + type);
        }

        Object enumValues = schema.get("enum");
        if (enumValues != null && !requireList(enumValues, path + ".enum").contains(value)) {
            throw new TestCaseValidationException(path + " 不在允许的 enum 中");
        }

        if (value instanceof Map<?, ?> objectValue) {
            validateObject(schema, objectValue, path);
        } else if (value instanceof List<?> listValue) {
            validateArray(schema, listValue, path);
        } else if (value instanceof String stringValue) {
            validateString(schema, stringValue, path);
        } else if (value instanceof Number numberValue) {
            validateNumber(schema, numberValue, path);
        }
    }

    private void validateObject(Map<String, Object> schema, Map<?, ?> value, String path) {
        Object required = schema.get("required");
        if (required != null) {
            for (Object requiredName : requireList(required, path + ".required")) {
                if (!value.containsKey(requiredName)) {
                    throw new TestCaseValidationException(path + " 缺少必填参数: " + requiredName);
                }
            }
        }

        Map<?, ?> properties = schema.get("properties") == null
                ? Map.of()
                : requireMap(schema.get("properties"), path + ".properties");
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String propertyName)) {
                throw new TestCaseValidationException(path + " 的参数名必须是字符串");
            }
            Object propertySchema = properties.get(propertyName);
            if (propertySchema != null) {
                validateValue(requireStringObjectMap(propertySchema, path + "." + propertyName),
                        entry.getValue(), path + "." + propertyName);
                continue;
            }
            Object additional = schema.get("additionalProperties");
            if (Boolean.FALSE.equals(additional)) {
                throw new TestCaseValidationException(path + " 包含未声明参数: " + propertyName);
            }
            if (additional instanceof Map<?, ?>) {
                validateValue(requireStringObjectMap(additional, path + "." + propertyName),
                        entry.getValue(), path + "." + propertyName);
            }
        }
    }

    private void validateArray(Map<String, Object> schema, List<?> value, String path) {
        requireSizeAtLeast(value.size(), schema.get("minItems"), path, "minItems");
        requireSizeAtMost(value.size(), schema.get("maxItems"), path, "maxItems");
        if (schema.get("items") instanceof Map<?, ?> itemSchema) {
            Map<String, Object> normalizedItemSchema = requireStringObjectMap(itemSchema, path + ".items");
            for (int index = 0; index < value.size(); index++) {
                validateValue(normalizedItemSchema, value.get(index), path + "[" + index + "]");
            }
        }
    }

    private void validateString(Map<String, Object> schema, String value, String path) {
        requireSizeAtLeast(value.length(), schema.get("minLength"), path, "minLength");
        requireSizeAtMost(value.length(), schema.get("maxLength"), path, "maxLength");
        if (schema.get("pattern") instanceof String regex && !Pattern.compile(regex).matcher(value).find()) {
            throw new TestCaseValidationException(path + " 不匹配 pattern");
        }
    }

    private void validateNumber(Map<String, Object> schema, Number value, String path) {
        BigDecimal decimal = new BigDecimal(value.toString());
        if (schema.get("minimum") instanceof Number minimum
                && decimal.compareTo(new BigDecimal(minimum.toString())) < 0) {
            throw new TestCaseValidationException(path + " 小于 minimum");
        }
        if (schema.get("maximum") instanceof Number maximum
                && decimal.compareTo(new BigDecimal(maximum.toString())) > 0) {
            throw new TestCaseValidationException(path + " 大于 maximum");
        }
    }

    private boolean matchesAnyType(Object type, Object value) {
        if (type instanceof String singleType) {
            return matchesType(singleType, value);
        }
        return requireList(type, "type").stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(candidate -> matchesType(candidate, value));
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "null" -> value == null;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof java.math.BigInteger;
            default -> false;
        };
    }

    private boolean allowsType(Object type, String expected) {
        return type instanceof String singleType
                ? expected.equals(singleType)
                : requireList(type, "type").contains(expected);
    }

    private void validateNonNegativeInteger(Object value, String path) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Number number)
                || new BigDecimal(number.toString()).stripTrailingZeros().scale() > 0
                || number.longValue() < 0) {
            throw new TestCaseValidationException(path + " 必须是非负整数");
        }
    }

    private void validateNumberKeyword(Object value, String path) {
        if (value != null && !(value instanceof Number)) {
            throw new TestCaseValidationException(path + " 必须是数值");
        }
    }

    private void validateOrderedBounds(Map<String, Object> schema, String minimumKey, String maximumKey, String path) {
        Object minimum = schema.get(minimumKey);
        Object maximum = schema.get(maximumKey);
        if (minimum == null || maximum == null) {
            return;
        }
        if (!(minimum instanceof Number min) || !(maximum instanceof Number max)) {
            throw new TestCaseValidationException(path + " 的 " + minimumKey + " 和 " + maximumKey + " 必须是数值");
        }
        if (new BigDecimal(min.toString()).compareTo(new BigDecimal(max.toString())) > 0) {
            throw new TestCaseValidationException(path + " 的 " + minimumKey + " 不能大于 " + maximumKey);
        }
    }

    private void requireSizeAtLeast(int actual, Object expected, String path, String keyword) {
        if (expected instanceof Number number && actual < number.intValue()) {
            throw new TestCaseValidationException(path + " 长度小于 " + keyword);
        }
    }

    private void requireSizeAtMost(int actual, Object expected, String path, String keyword) {
        if (expected instanceof Number number && actual > number.intValue()) {
            throw new TestCaseValidationException(path + " 长度大于 " + keyword);
        }
    }

    private Map<?, ?> requireMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new TestCaseValidationException(path + " 必须是对象");
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireStringObjectMap(Object value, String path) {
        Map<?, ?> map = requireMap(value, path);
        if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new TestCaseValidationException(path + " 的键必须是字符串");
        }
        return (Map<String, Object>) map;
    }

    private List<?> requireList(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new TestCaseValidationException(path + " 必须是数组");
        }
        return list;
    }
}
