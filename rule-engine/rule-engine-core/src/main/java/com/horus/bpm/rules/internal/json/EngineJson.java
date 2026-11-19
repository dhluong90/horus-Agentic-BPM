package com.horus.bpm.rules.internal.json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The engine's own JSON and YAML mappers. Engine payloads never go through the host's Jackson
 * configuration (ADR-0021, TR-EMB-07). Every number is read as {@code BigDecimal} or an integer, never
 * through {@code double}; duplicate keys are rejected.
 */
public final class EngineJson {

    /** JSON mapper for engine payloads and stored documents. */
    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /** YAML mapper for imported source documents. */
    public static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Node factory that keeps {@code BigDecimal} values exactly. */
    public static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private EngineJson() {
    }
}
