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

    URI versionedEndpoint(String version, String relativePath) {
        String path = normalizeRelativePath(relativePath);
        String normalizedVersion = normalizeRelativePath(version);
        String versionPrefix = normalizedVersion + "/";
        String baseUrl = baseUrl();
        String versionSuffix = "/" + normalizedVersion;
        boolean pathIncludesVersion = path.equalsIgnoreCase(normalizedVersion)
                || path.regionMatches(true, 0, versionPrefix, 0, versionPrefix.length());
        boolean baseIncludesVersion = baseUrl.length() >= versionSuffix.length()
                && baseUrl.regionMatches(
                true,
                baseUrl.length() - versionSuffix.length(),
                versionSuffix,
                0,
                versionSuffix.length()
        );
        URI endpointBase = baseUri;
        if (pathIncludesVersion && baseIncludesVersion) {
            endpointBase = URI.create(baseUrl.substring(0, baseUrl.length() - versionSuffix.length()));
        }
        return resolveEndpoint(endpointBase, path);
    }

    static URI resolveEndpoint(URI baseUri, String relativePath) {
        String path = normalizeRelativePath(relativePath);
        return URI.create(stripTrailingSlash(baseUri.toString()) + "/" + path);
    }

    private static String normalizeRelativePath(String relativePath) {
        String path = relativePath == null ? "" : relativePath.trim().replaceFirst("^/+", "");
        if (path.isEmpty() || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("接口路径必须是有效的相对路径");
        }
        return path;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
