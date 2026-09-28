package io.testforge.casecatalog.testcase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.testforge.casecatalog.testcase.asset.model.CaseAssetView;
import io.testforge.casecatalog.testcase.asset.service.CaseAssetService;
import io.testforge.casecatalog.testcase.model.CaseDefinition;
import io.testforge.casecatalog.testcase.model.ExecutionRequirement;
import io.testforge.casecatalog.testcase.model.InteractionMode;
import io.testforge.casecatalog.testcase.model.LeaseScope;
import io.testforge.casecatalog.testcase.model.TestCaseKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CaseDefinitionParser {
    public static final String API_VERSION = "testforge.io/v1alpha1";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final CaseAssetService assetService;
    private final ParameterSchemaValidator schemaValidator = new ParameterSchemaValidator();

    public CaseDefinitionParser(CaseAssetService assetService) {
        this.assetService = assetService;
    }

    public CaseDefinition parse(UUID projectId, String yaml, boolean materializeInline) {
        if (yaml == null || yaml.isBlank()) throw invalid("YAML 不能为空");
        Map<String, Object> root = read(yaml);
        requireKeys(root, Set.of("apiVersion", "kind", "metadata", "spec"), "root");
        if (!API_VERSION.equals(text(root, "apiVersion", "apiVersion"))) {
            throw invalid("apiVersion 只支持 " + API_VERSION);
        }
        if (!"TestCase".equals(text(root, "kind", "kind"))) throw invalid("kind 必须是 TestCase");
        Map<String, Object> metadata = object(root, "metadata", "metadata");
        requireKeys(metadata, Set.of("name", "tags"), "metadata");
        String name = bounded(text(metadata, "name", "metadata.name"), 200, "metadata.name");
        Set<String> tags = tags(metadata.get("tags"));

        Map<String, Object> spec = object(root, "spec", "spec");
        requireKeys(spec, Set.of("type", "timeoutSeconds", "execution", "parameters", "script"), "spec");
        TestCaseKind caseKind = switch (text(spec, "type", "spec.type").toLowerCase(Locale.ROOT)) {
            case "assertion" -> TestCaseKind.ASSERTION;
            case "fixture" -> TestCaseKind.FIXTURE;
            default -> throw invalid("spec.type 只支持 assertion 或 fixture");
        };
        int timeout = integer(spec.get("timeoutSeconds"), "spec.timeoutSeconds");
        if (timeout < 1 || timeout > 86_400) throw invalid("spec.timeoutSeconds 必须在 1 到 86400 之间");
        Map<String, Object> parameters = optionalObject(spec.get("parameters"), "spec.parameters");
        if (parameters.isEmpty()) parameters = Map.of("type", "object", "additionalProperties", true);
        schemaValidator.validateSchema(parameters);
        ExecutionRequirement execution = execution(object(spec, "execution", "spec.execution"));
        CaseDefinition.ScriptDefinition script = script(
                projectId, name, execution, object(spec, "script", "spec.script"), materializeInline
        );
        String digest = sha256(yaml.getBytes(StandardCharsets.UTF_8));
        return new CaseDefinition(
                yaml, digest, name, caseKind, tags, Map.copyOf(parameters), timeout, execution, script
        );
    }

    private CaseDefinition.ScriptDefinition script(
            UUID projectId,
            String caseName,
            ExecutionRequirement execution,
            Map<String, Object> value,
            boolean materializeInline
    ) {
        requireKeys(value, Set.of("type", "asset", "entrypoint", "language", "content"), "spec.script");
        String type = text(value, "type", "spec.script.type").toLowerCase(Locale.ROOT);
        if ("inline".equals(type)) {
            if (!Set.of("pytest-http", "playwright-web").contains(execution.executorType())) {
                throw invalid("inline script 只支持 pytest-http 或 playwright-web；Airtest 请上传 .air.zip");
            }
            String language = text(value, "language", "spec.script.language").toLowerCase(Locale.ROOT);
            if (!"python".equals(language)) throw invalid("inline script.language 只支持 python");
            String content = text(value, "content", "spec.script.content");
            String entrypoint = optionalText(value.get("entrypoint"));
            if (entrypoint == null) entrypoint = slug(caseName) + "_test.py";
            validateEntrypoint(entrypoint);
            String checksum = sha256(content.getBytes(StandardCharsets.UTF_8));
            if (!materializeInline) {
                return new CaseDefinition.ScriptDefinition(type, null, entrypoint, "inline://pending", checksum, content);
            }
            CaseAssetView asset = assetService.storeInline(projectId, entrypoint, content);
            return new CaseDefinition.ScriptDefinition(
                    type, asset.id(), entrypoint, withEntrypoint(asset.uri(), entrypoint), asset.sha256(), content
            );
        }
        if (!"asset".equals(type)) throw invalid("spec.script.type 只支持 inline 或 asset");
        UUID assetId = assetId(text(value, "asset", "spec.script.asset"));
        CaseAssetView asset = assetService.requireOwned(projectId, assetId);
        String entrypoint = optionalText(value.get("entrypoint"));
        if (entrypoint == null) {
            if (asset.entrypoints().size() != 1) {
                throw invalid("Asset 必须恰好包含一个主执行入口");
            }
            entrypoint = asset.entrypoints().getFirst();
        }
        validateEntrypoint(entrypoint);
        if (!asset.entrypoints().contains(entrypoint)) {
            throw invalid("spec.script.entrypoint 不存在于 Asset 中: " + entrypoint);
        }
        if ("airtest".equals(execution.executorType())
                && !entrypoint.toLowerCase(Locale.ROOT).endsWith(".air")) {
            throw invalid("Airtest entrypoint 必须指向 Asset 内的 .air 目录");
        }
        if ("playwright-web".equals(execution.executorType())
                && !entrypoint.toLowerCase(Locale.ROOT).endsWith(".py")) {
            throw invalid("Playwright Web entrypoint 必须指向 Asset 内的 .py 文件");
        }
        return new CaseDefinition.ScriptDefinition(
                type, asset.id(), entrypoint, withEntrypoint(asset.uri(), entrypoint), asset.sha256(), null
        );
    }

    private ExecutionRequirement execution(Map<String, Object> value) {
        requireKeys(value, Set.of("executor", "interaction", "capabilities", "resourceProfile", "leaseScope", "sessionKey"), "spec.execution");
        String executor = text(value, "executor", "spec.execution.executor").toLowerCase(Locale.ROOT);
        if (!Set.of("pytest-http", "playwright-web", "airtest").contains(executor)) throw invalid("execution.executor 不支持: " + executor);
        InteractionMode interaction;
        try { interaction = InteractionMode.valueOf(text(value, "interaction", "spec.execution.interaction").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw invalid("execution.interaction 只支持 HEADLESS 或 UI"); }
        if (("airtest".equals(executor)) != (interaction == InteractionMode.UI)) {
            throw invalid("airtest 必须使用 UI，pytest-http 与 playwright-web 必须使用 HEADLESS");
        }
        Set<String> capabilities = new LinkedHashSet<>(strings(value.get("capabilities"), "spec.execution.capabilities"));
        String profile = bounded(text(value, "resourceProfile", "spec.execution.resourceProfile"), 64, "resourceProfile");
        LeaseScope leaseScope;
        try { leaseScope = LeaseScope.valueOf(text(value, "leaseScope", "spec.execution.leaseScope").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw invalid("execution.leaseScope 只支持 CASE 或 SUBFLOW"); }
        String sessionKey = optionalText(value.get("sessionKey"));
        if (leaseScope == LeaseScope.SUBFLOW && sessionKey == null) throw invalid("SUBFLOW leaseScope 必须提供 sessionKey");
        return new ExecutionRequirement(executor, interaction, capabilities, profile, leaseScope, sessionKey);
    }

    private Map<String, Object> read(String yaml) {
        try {
            Map<String, Object> value = yamlMapper.readValue(yaml, MAP);
            if (value == null) throw invalid("YAML 必须包含对象");
            return value;
        } catch (JsonProcessingException error) {
            throw invalid("YAML 语法错误: " + error.getOriginalMessage());
        }
    }

    private static void requireKeys(Map<String, Object> value, Set<String> allowed, String path) {
        List<String> unknown = value.keySet().stream().filter(key -> !allowed.contains(key)).sorted().toList();
        if (!unknown.isEmpty()) throw invalid(path + " 含未知字段: " + String.join(", ", unknown));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> source, String key, String path) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> map)) throw invalid(path + " 必须是对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), itemValue));
        return result;
    }

    private static Map<String, Object> optionalObject(Object value, String path) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> map)) throw invalid(path + " 必须是对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Map<String, Object> source, String key, String path) {
        String value = optionalText(source.get(key));
        if (value == null) throw invalid(path + " 不能为空");
        return value;
    }

    private static String optionalText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.trim();
    }

    private static String bounded(String value, int max, String path) {
        if (value.length() > max) throw invalid(path + " 长度不能超过 " + max);
        return value;
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number)) throw invalid(path + " 必须是整数");
        return number.intValue();
    }

    private static Set<String> tags(Object value) {
        return new LinkedHashSet<>(strings(value, "metadata.tags"));
    }

    private static List<String> strings(Object value, String path) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw invalid(path + " 必须是字符串数组");
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) throw invalid(path + " 只能包含非空字符串");
            result.add(text.trim());
        }
        if (result.stream().distinct().count() != result.size()) throw invalid(path + " 不能包含重复项");
        return List.copyOf(result);
    }

    private static UUID assetId(String uri) {
        String prefix = "testforge://assets/";
        if (!uri.startsWith(prefix)) throw invalid("spec.script.asset 必须使用 testforge://assets/{id}");
        String raw = uri.substring(prefix.length()).split("[#?]", 2)[0];
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException error) { throw invalid("spec.script.asset 包含非法 UUID"); }
    }

    private static void validateEntrypoint(String value) {
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || List.of(path.split("/")).contains("..")) {
            throw invalid("script.entrypoint 必须是资源包内相对路径");
        }
        if (path.length() > 512) throw invalid("script.entrypoint 长度不能超过 512");
    }

    private static String withEntrypoint(String uri, String entrypoint) {
        return uri + "#" + entrypoint.replace(" ", "%20");
    }

    private static String slug(String value) {
        String result = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return result.isBlank() ? "case" : result;
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TestCaseValidationException invalid(String message) {
        return new TestCaseValidationException(message);
    }
}
