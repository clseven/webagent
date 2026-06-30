package com.example.sandbox.web.service.impl;

import com.example.sandbox.aio.AioClient;
import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SandboxService;
import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能管理服务实现。
 *
 * <p>本地仓库扫描只作为<b>上传源定位</b>：启动后由前端配置根目录，扫到的 skill 用于
 * {@code FileSyncService.syncSkill} 把文件推到沙箱默认标准目录。</p>
 *
 * <p>运行期 skill 发现走 {@link #discoverFromSandbox(String)}：单次 shell 列目录 + 多次 AIO file/read，
 * 解析 frontmatter 后返回 {@link Skill}。Agent 执行链路统一使用此方法，无缓存，每次实时读取。</p>
 *
 * <p>本地仓库查询（{@link #listSkills()} / {@link #getSkill(String)}）每次实时读本地文件系统，无缓存，不参与运行时 skill 读取。</p>
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);

    /** 本地技能包递归扫描深度，覆盖 {@code bundle/skill/<id>/SKILL.md} 等常见打包结构。 */
    private static final int LOCAL_FIND_MAX_DEPTH = 8;

    /** 沙箱技能包递归扫描深度，覆盖 {@code /home/gem/skills/<package>/skill/<id>/SKILL.md}。 */
    private static final int SANDBOX_FIND_MAX_DEPTH = 8;

    /** 沙箱客户端工厂；用 @Lazy 打破与 {@code SandboxServiceImpl} 的潜在循环依赖。 */
    @Autowired
    @Lazy
    private SandboxClientFactory sandboxClientFactory;

    /** 用于判断沙箱是否就绪；同样 @Lazy。 */
    @Autowired
    @Lazy
    private SandboxService sandboxService;

    /** 本地仓库根路径，由前端 set-root 接口设置。 */
    private volatile String skillRootPath;

    @Override
    public List<Skill> listSkills() {
        if (skillRootPath == null || skillRootPath.isBlank()) {
            return List.of();
        }
        return loadSkillsFromDirectory(skillRootPath);
    }

    @Override
    public Skill getSkill(String skillId) throws IOException {
        if (skillRootPath == null || skillRootPath.isBlank()) {
            throw new SkillNotFoundException(skillId);
        }
        return listSkills().stream()
                .filter(skill -> skillId.equals(skill.getId()))
                .findFirst()
                .orElseThrow(() -> new SkillNotFoundException(skillId));
    }

    @Override
    public List<Skill> loadSkillsFromDirectory(String directory) {
        log.info("扫描本地技能仓库: {}", directory);
        List<Skill> result = new ArrayList<>();
        try {
            Path skillDir = Path.of(directory);
            if (!Files.exists(skillDir)) {
                log.warn("本地技能仓库不存在: {}", directory);
                return result;
            }

            Map<String, SkillCandidate> candidates = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.walk(skillDir, LOCAL_FIND_MAX_DEPTH)) {
                for (Path skillFile : stream
                        .filter(Files::isRegularFile)
                        .filter(this::isSkillMarkdownFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    Path path = skillFile.getParent();
                    if (path == null || path.getFileName() == null) {
                        continue;
                    }
                    try {
                        String content = Files.readString(skillFile);
                        String skillId = path.getFileName().toString();
                        Skill skill = parseSkillMd(skillId, content, path, skillFile, null, null);
                        boolean standardPath = isStandardLocalSkillPath(skillDir, skillFile);
                        putSkillCandidate(candidates, skill, standardPath, skillFile.toString());
                    } catch (IOException e) {
                        log.error("读取技能文件失败: {}", skillFile, e);
                    }
                }
            }
            result.addAll(candidates.values().stream().map(SkillCandidate::skill).toList());

            log.info("本地技能仓库扫描完成: {} 个", result.size());
        } catch (IOException e) {
            log.error("扫描本地技能仓库失败: {}", directory, e);
        }
        return result;
    }

    /**
     * 判断路径是否为技能入口文件，兼容不同打包工具产生的大小写差异。
     *
     * @param path 待检查文件路径
     * @return 是技能入口文件时返回 true
     */
    private boolean isSkillMarkdownFile(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && "SKILL.md".equalsIgnoreCase(fileName.toString());
    }

    /**
     * 判断本地技能是否使用旧版标准结构：根目录下直接是 {@code <id>/SKILL.md}。
     *
     * @param root      本地技能仓库根目录
     * @param skillFile 技能入口文件
     * @return 使用旧版标准结构时返回 true
     */
    private boolean isStandardLocalSkillPath(Path root, Path skillFile) {
        try {
            return root.relativize(skillFile).getNameCount() == 2;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 按技能 ID 去重，并在同名冲突时优先保留旧版标准结构，保证兼容历史目录布局。
     *
     * @param candidates   当前候选集
     * @param skill        新发现的技能
     * @param standardPath 新发现路径是否为旧版标准结构
     * @param sourcePath   日志展示用的发现路径
     */
    private void putSkillCandidate(Map<String, SkillCandidate> candidates, Skill skill,
                                   boolean standardPath, String sourcePath) {
        SkillCandidate existing = candidates.get(skill.getId());
        if (existing == null) {
            candidates.put(skill.getId(), new SkillCandidate(skill, standardPath, sourcePath));
            return;
        }
        if (!existing.standardPath() && standardPath) {
            log.warn("发现重复技能 ID {}，优先使用标准结构路径: {}", skill.getId(), sourcePath);
            candidates.put(skill.getId(), new SkillCandidate(skill, true, sourcePath));
            return;
        }
        log.warn("发现重复技能 ID {}，已跳过路径: {}，保留路径: {}",
                skill.getId(), sourcePath, existing.sourcePath());
    }

    @Override
    public void setSkillRootPath(String rootPath) {
        this.skillRootPath = rootPath;
        log.info("技能根目录已设置: {}", rootPath);
    }

    @Override
    public List<Skill> discoverFromSandbox(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        if (sandboxService == null || !sandboxService.hasSandbox(sessionId)) {
            log.debug("沙箱未就绪，跳过 skill 发现: sessionId={}", sessionId);
            return List.of();
        }

        AioClient client;
        try {
            client = sandboxClientFactory.getAioClient(sessionId);
        } catch (Exception e) {
            log.warn("获取沙箱客户端失败 sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
        if (client == null) {
            return List.of();
        }

        // 一次 shell 列出所有技能入口文件；目录不存在或为空时输出空字符串
        String findCmd = "find " + shellQuote(Skill.SANDBOX_SKILL_ROOT)
                + " -maxdepth " + SANDBOX_FIND_MAX_DEPTH
                + " -type f \\( -name SKILL.md -o -name skill.md \\) 2>/dev/null";
        String output;
        try {
            output = client.execCommand(findCmd);
        } catch (Exception e) {
            log.warn("沙箱发现 skill 命令执行失败 sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
        if (output == null || output.isBlank()) {
            return List.of();
        }

        String prefix = Skill.SANDBOX_SKILL_ROOT + "/";
        Map<String, SkillCandidate> candidates = new LinkedHashMap<>();
        List<String> skillFiles = output.lines()
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .sorted()
                .toList();
        for (String filePath : skillFiles) {
            if (!filePath.startsWith(prefix) || !isSandboxSkillMarkdownFile(filePath)) {
                continue;
            }
            String basePath = parentPath(filePath);
            String skillId = lastPathSegment(basePath);
            if (skillId.isBlank()) {
                continue;
            }

            try {
                String content = client.readFile(filePath);
                Skill skill = parseSkillMd(skillId, content, null, null, basePath, filePath);
                skill.setSource(Skill.Source.SANDBOX);
                boolean standardPath = isStandardSandboxSkillPath(skillId, filePath);
                putSkillCandidate(candidates, skill, standardPath, filePath);
            } catch (Exception e) {
                log.warn("沙箱发现 skill 解析失败 sessionId={} skillId={}: {}", sessionId, skillId, e.getMessage());
            }
        }
        List<Skill> result = candidates.values().stream().map(SkillCandidate::skill).toList();
        log.debug("沙箱发现 skill 数量 sessionId={} count={}", sessionId, result.size());
        return result;
    }

    /**
     * 解析 SKILL.md 内容（含 YAML frontmatter）。
     *
     * @param skillId   技能 ID（目录名）
     * @param content   文件全文
     * @param localPath            本地 skill 根目录；沙箱发现传 null
     * @param localSkillFile       本地技能入口文件；沙箱发现传 null
     * @param sandboxBasePath      沙箱 skill 真实根目录；本地发现传 null
     * @param sandboxSkillFilePath 沙箱技能入口文件；本地发现传 null
     * @return 解析得到的技能
     */
    private Skill parseSkillMd(String skillId, String content, Path localPath, Path localSkillFile,
                               String sandboxBasePath, String sandboxSkillFilePath) {
        String name = "";
        String description = "";
        Map<String, Object> frontmatterMap = Collections.emptyMap();

        // 提取并解析 YAML frontmatter
        if (content != null && content.startsWith("---")) {
            String frontmatterText = extractFrontmatterText(content);
            if (frontmatterText != null) {
                try {
                    Yaml yaml = new Yaml();
                    Map<String, Object> parsed = yaml.load(frontmatterText);
                    if (parsed != null) {
                        frontmatterMap = parsed;
                        name = getStr(frontmatterMap, "name");
                        description = getStr(frontmatterMap, "description");
                    }
                } catch (Exception e) {
                    log.warn("解析 SKILL.md frontmatter 失败 (skill={}): {}", skillId, e.getMessage());
                }
            }
        }

        if (description.isEmpty() && content != null) {
            description = extractDescriptionFromContent(content);
        }

        return new Skill(skillId, name, description, localPath, localSkillFile,
                sandboxBasePath, sandboxSkillFilePath, frontmatterMap);
    }

    /**
     * 判断沙箱路径是否为技能入口文件。
     *
     * @param path 沙箱绝对路径
     * @return 是技能入口文件时返回 true
     */
    private boolean isSandboxSkillMarkdownFile(String path) {
        String lowerPath = path.toLowerCase(java.util.Locale.ROOT);
        return lowerPath.endsWith("/skill.md");
    }

    /**
     * 判断沙箱技能是否使用旧版标准结构：{@code /home/gem/skills/<id>/SKILL.md}。
     *
     * @param skillId  技能 ID
     * @param filePath 沙箱入口文件路径
     * @return 使用旧版标准结构时返回 true
     */
    private boolean isStandardSandboxSkillPath(String skillId, String filePath) {
        String prefix = Skill.SANDBOX_SKILL_ROOT + "/";
        if (!filePath.startsWith(prefix)) {
            return false;
        }
        String relative = filePath.substring(prefix.length());
        String[] parts = relative.split("/");
        return parts.length == 2 && skillId.equals(parts[0]) && isSandboxSkillMarkdownFile(filePath);
    }

    /**
     * 取 Unix 风格路径的父目录。
     *
     * @param path 绝对路径
     * @return 父目录路径；没有父目录时返回空字符串
     */
    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    /**
     * 取 Unix 风格路径的最后一个片段。
     *
     * @param path 绝对路径或相对路径
     * @return 最后一个路径片段；路径为空时返回空字符串
     */
    private String lastPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    /**
     * 把字符串包装成单引号 shell 参数，避免路径中出现空格或特殊字符时破坏 find 命令。
     *
     * @param value 原始参数
     * @return 转义后的 shell 参数
     */
    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 技能发现候选项，记录是否来自旧版标准结构用于同名去重。
     *
     * @param skill        技能元数据
     * @param standardPath 是否为旧版标准结构
     * @param sourcePath   发现该技能的入口文件路径
     */
    private record SkillCandidate(Skill skill, boolean standardPath, String sourcePath) {
    }

    /**
     * 从内容中提取 YAML frontmatter 文本（两个 --- 独占行之间的部分）。
     *
     * @param content SKILL.md 全文
     * @return frontmatter 段落文本；缺失结束分隔符时返回 null
     */
    private String extractFrontmatterText(String content) {
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                return sb.toString();
            }
            sb.append(lines[i]).append("\n");
        }
        return null;
    }

    /**
     * 从 Map 中安全取字符串值。
     *
     * @param map frontmatter Map
     * @param key 键
     * @return 字符串值；缺失返回空串
     */
    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    /**
     * 从 Markdown 内容提取描述（标题后的第一段）。
     *
     * @param content SKILL.md 全文
     * @return 提取到的描述
     */
    private String extractDescriptionFromContent(String content) {
        // 跳过 frontmatter：找到第二个独占 --- 行，取其后的内容
        String body = content;
        if (content.startsWith("---")) {
            String[] lines = content.split("\n");
            boolean foundEnd = false;
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                if (!foundEnd) {
                    if (lines[i].trim().equals("---")) {
                        foundEnd = true;
                    }
                } else {
                    bodyBuilder.append(lines[i]).append("\n");
                }
            }
            if (foundEnd) {
                body = bodyBuilder.toString().trim();
            }
        }

        for (String line : body.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                int dotIndex = line.indexOf("。");
                if (dotIndex > 0) {
                    return line.substring(0, dotIndex + 1);
                }
                return line.length() > 100 ? line.substring(0, 100) + "..." : line;
            }
        }
        return "";
    }
}
