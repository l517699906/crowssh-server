package com.llf.ai.domain.agent.service.model;

import java.net.URI;
import java.net.URISyntaxException;

record RuntimeModelConnection(String provider,
                              String protocol,
                              URI baseUri,
                              String apiKey,
                              String authType,
                              String authHeader,
                              String authPrefix,
                              String modelListPath) {

    String baseUrl() {
        return stripTrailingSlash(baseUri.toString());
    }

    String origin() {
        try {
            return new URI(
                    baseUri.getScheme(),
                    null,
                    baseUri.getHost(),
                    baseUri.getPort(),
                    null,
                    null,
                    null
            ).toString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("服务地址必须是有效的 HTTPS 地址");
        }
    }

    URI endpoint(String relativePath) {
        return resolveEndpoint(baseUri, relativePath);
    }

    static URI resolveEndpoint(URI baseUri, String relativePath) {
        String path = relativePath == null ? "" : relativePath.trim().replaceFirst("^/+", "");
        if (path.isEmpty() || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("接口路径必须是有效的相对路径");
        }
        return URI.create(stripTrailingSlash(baseUri.toString()) + "/" + path);
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
