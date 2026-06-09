package com.example.sandbox.web.model.entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 技能（不存数据库，只存在本地文件系统）
 *
 * <p>支持三级渐进式披露：</p>
 * <ol>
 *   <li>元数据层：id + description（简历模式，约 30-50 token）</li>
 *   <li>详细层：SKILL.md 完整内容（按需加载）</li>
 *   <li>资源层：references/、scripts/ 下的辅助文件（用到才取）</li>
 * </ol>
 *
 * @author example
 * @date 2026/05/14
 */
public class Skill {

    /**
     * 技能唯一标识（目录名）
     */
    private final String id;

    /**
     * 名称（从 frontmatter 的 name 字段提取）
     */
    private final String name;

    /**
     * 描述（展示用，让 agent 判断是否相关）
     */
    private final String description;

    /**
     * 本地目录路径
     */
    private final Path localPath;

    public Skill(String id, String name, String description, Path localPath) {
        this.id = id;
        this.name = name != null ? name : id;
        this.description = description;
        this.localPath = localPath;
    }

    /**
     * 生成第一层元数据（简历模式）
     * 格式：skill_id: description
     * 约 30-50 token per skill
     */
    public String toMetadataLine() {
        return String.format("- %s: %s", id, description);
    }

    /**
     * 加载 SKILL.md 完整内容（第二层）
     */
    public String getContent() throws IOException {
        return Files.readString(getSkillFile());
    }

    /**
     * 加载引用文件（第三层）
     *
     * @param relativePath 相对于 skill 目录的路径，如 "references/testing-anti-patterns.md"
     * @return 文件内容
     */
    public String getReferenceFile(String relativePath) throws IOException {
        Path file = localPath.resolve(relativePath);
        if (!Files.exists(file)) {
            throw new IOException("Reference file not found: " + relativePath);
        }
        return Files.readString(file);
    }

    /**
     * 列出所有可用的引用文件（供 agent 发现）
     */
    public String listAvailableReferences() throws IOException {
        List<Path> refs = listReferences();
        if (refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Available references:\n");
        for (Path ref : refs) {
            sb.append("  - ").append(localPath.relativize(ref).toString().replace("\\", "/")).append("\n");
        }
        return sb.toString();
    }

    /**
     * SKILL.md 文件路径
     */
    public Path getSkillFile() {
        return localPath.resolve("SKILL.md");
    }

    /**
     * scripts 目录路径（如果有）
     */
    public Path getScriptsDir() {
        return localPath.resolve("scripts");
    }

    /**
     * references 目录路径（如果有）
     */
    public Path getReferencesDir() {
        return localPath.resolve("references");
    }

    /**
     * 是否有脚本目录
     */
    public boolean hasScripts() {
        return Files.exists(getScriptsDir()) && Files.isDirectory(getScriptsDir());
    }

    /**
     * 获取所有脚本文件列表
     */
    public List<Path> listScripts() throws IOException {
        if (!hasScripts()) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(getScriptsDir())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".sh") || name.endsWith(".py")
                                || name.endsWith(".js") || name.endsWith(".cjs");
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * 获取脚本文件路径
     */
    public Path getScript(String scriptName) {
        return getScriptsDir().resolve(scriptName);
    }

    /**
     * 获取所有引用文件列表
     */
    public List<Path> listReferences() throws IOException {
        Path refDir = getReferencesDir();
        if (!Files.exists(refDir) || !Files.isDirectory(refDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(refDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Path getLocalPath() {
        return localPath;
    }

    @Override
    public String toString() {
        return "Skill{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", localPath=" + localPath +
                '}';
    }
}
