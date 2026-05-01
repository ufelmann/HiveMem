package com.hivemem.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads extraction profile YAMLs from a classpath directory. */
public final class ExtractionProfileLoader {

    private static final Logger log = LoggerFactory.getLogger(ExtractionProfileLoader.class);

    private ExtractionProfileLoader() {}

    public static Map<String, ExtractionProfile> loadFromClasspath(String basePath) {
        String pattern = "classpath*:" + basePath + "*.yaml";
        Map<String, ExtractionProfile> out = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(pattern);
            Yaml yaml = new Yaml();
            for (Resource r : resources) {
                String fileName = r.getFilename();
                if (fileName == null || !fileName.endsWith(".yaml")) continue;
                String typeFromFile = fileName.substring(0, fileName.length() - ".yaml".length());
                try (InputStream in = r.getInputStream()) {
                    Map<String, Object> raw = yaml.load(in);
                    ExtractionProfile profile = parse(raw);
                    if (!profile.type().equals(typeFromFile)) {
                        log.warn("Profile filename {} does not match type {}; using filename",
                                fileName, profile.type());
                        profile = new ExtractionProfile(
                                typeFromFile,
                                profile.prompt(),
                                profile.requiredFacts(),
                                profile.optionalFacts(),
                                profile.summaryTemplate(),
                                profile.tagsToApply());
                    }
                    out.put(profile.type(), profile);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load extraction profiles from {}: {}", pattern, e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ExtractionProfile parse(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("Empty profile YAML");
        String type = str(raw.get("type"));
        String prompt = str(raw.get("prompt"));
        List<String> required = strList(raw.get("required_facts"));
        List<String> optional = strList(raw.get("optional_facts"));
        String summaryTemplate = raw.get("summary_template") == null
                ? null : str(raw.get("summary_template"));
        List<String> tags = strList(raw.get("tags_to_apply"));
        return new ExtractionProfile(type, prompt, required, optional, summaryTemplate, tags);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("Expected list, got: " + o.getClass());
    }
}
