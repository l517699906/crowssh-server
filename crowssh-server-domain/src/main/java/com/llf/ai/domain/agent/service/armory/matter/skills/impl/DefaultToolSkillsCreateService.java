package com.llf.ai.domain.agent.service.armory.matter.skills.impl;

import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
public class DefaultToolSkillsCreateService implements ToolSkillsCreateService {

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception {

        String type = toolSkills.getType();
        String path = toolSkills.getPath();

        if ("directory".equals(type)) {
            return new ToolCallback[]{SkillsTool.builder()
                    .addSkillsDirectory(requireDirectoryPath(path))
                    .build()};
        }

        if ("resource".equals(type)) {
            Path materialized = materializeClasspathDirectory(path);
            return new ToolCallback[]{SkillsTool.builder()
                    .addSkillsDirectory(materialized.toString())
                    .build()};
        }

        throw new IllegalArgumentException("不支持的 Skills 类型: " + type);
    }

    private String requireDirectoryPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Skills 目录不能为空");
        }
        return path.trim();
    }

    /**
     * SkillsTool 只接受文件系统目录。应用打成 fat JAR 后，classpath 目录没有真实 File，
     * 因此把资源安全展开到临时目录再交给 SkillsTool。
     */
    private Path materializeClasspathDirectory(String path) throws IOException {
        String normalized = requireClasspathPath(path);
        ClassPathResource root = new ClassPathResource(normalized);
        try {
            Path file = root.getFile().toPath();
            if (Files.isDirectory(file)) {
                if (!containsSkillFile(file)) {
                    throw new IOException("Skills 目录中没有 SKILL.md: " + normalized);
                }
                return file;
            }
        } catch (IOException ignored) {
            // JAR 内资源没有真实文件路径，继续走展开逻辑。
        }

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + normalized + "/**/*");
        if (resources.length == 0) {
            throw new IOException("classpath Skills 目录不存在: " + normalized);
        }

        Path target = Files.createTempDirectory("crowssh-skills-");
        boolean hasSkill = false;
        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getURL().toExternalForm().endsWith("/")) {
                continue;
            }
            String relative = relativePath(resource, normalized);
            if (relative.isBlank() || relative.contains("..")) {
                throw new IOException("非法 Skills 资源路径: " + relative);
            }
            Path targetFile = target.resolve(relative).normalize();
            if (!targetFile.startsWith(target)) {
                throw new IOException("Skills 资源越界: " + relative);
            }
            Files.createDirectories(targetFile.getParent());
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            hasSkill |= targetFile.getFileName().toString().equals("SKILL.md");
        }

        if (!hasSkill) {
            throw new IOException("Skills 目录中没有 SKILL.md: " + normalized);
        }
        log.info("已展开 classpath Skills: source={}, target={}", normalized, target);
        return target;
    }

    private boolean containsSkillFile(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().equals("SKILL.md"));
        }
    }

    private String requireClasspathPath(String path) {
        String normalized = requireDirectoryPath(path).replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalArgumentException("非法 classpath Skills 路径: " + path);
        }
        return normalized;
    }

    private String relativePath(Resource resource, String root) throws IOException {
        String url = resource.getURL().toExternalForm();
        String marker = root + "/";
        int index = url.lastIndexOf(marker);
        if (index < 0) {
            throw new IOException("无法计算 Skills 资源相对路径: " + url);
        }
        return URLDecoder.decode(url.substring(index + marker.length()), StandardCharsets.UTF_8);
    }

}
