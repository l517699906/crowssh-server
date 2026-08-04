package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class RuntimeModelResponseParser {

    private static final int MAX_MODEL_COUNT = 500;
    private static final int MAX_MODEL_ID_LENGTH = 200;

    private RuntimeModelResponseParser() {
    }

    static List<String> parseOpenAi(byte[] payload, ObjectMapper objectMapper) {
        return parse(payload, objectMapper, "data", "id", false);
    }

    static List<String> parseGemini(byte[] payload, ObjectMapper objectMapper) {
        return parse(payload, objectMapper, "models", "name", true);
    }

    private static List<String> parse(byte[] payload,
                                      ObjectMapper objectMapper,
                                      String arrayField,
                                      String idField,
                                      boolean gemini) {
        try {
            JsonNode items = objectMapper.readTree(payload).path(arrayField);
            if (!items.isArray()) {
                throw new IllegalArgumentException("模型列表响应格式无效");
            }

            Set<String> models = new TreeSet<>();
            for (JsonNode item : items) {
                if (gemini && !supportsGeminiGeneration(item)) {
                    continue;
                }
                String id = item.path(idField).asText("").trim();
                if (gemini && id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (!id.isEmpty() && id.length() <= MAX_MODEL_ID_LENGTH) {
                    models.add(id);
                    if (models.size() >= MAX_MODEL_COUNT) {
                        break;
                    }
                }
            }
            if (models.isEmpty()) {
                throw new IllegalArgumentException("服务商未返回可用模型");
            }
            return List.copyOf(models);
        } catch (IOException error) {
            throw new IllegalArgumentException("模型列表响应格式无效");
        }
    }

    private static boolean supportsGeminiGeneration(JsonNode item) {
        JsonNode methods = item.path("supportedGenerationMethods");
        if (!methods.isArray()) {
            return true;
        }
        for (JsonNode method : methods) {
            String value = method.asText("");
            if ("generateContent".equals(value) || "streamGenerateContent".equals(value)) {
                return true;
            }
        }
        return false;
    }
}
