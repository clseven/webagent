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
import java.util.List;
import java.util.Map;

/**
 * 技能管理服务实现。
 *
 * <p>本地仓库扫描是<b>种子来源</b>：启动后由前端配置根目录，扫到的 skill 用于
 * {@code FileSyncService.syncSkill} 把文件推到沙箱 {@code /home/gem/skills/<id>/}。</p>
 *
 * <p>运行期 skill 发现走 {@link #discoverFromSandbox(String)}：单次 shell 列目录 + 多次 AIO file/read，
 * 解析 frontmatter 后返回 {@link Skill}。Agent 执行链路统一使用此方法，无缓存，每次实时读取。</p>
 *
 * <p>本地仓库查询（{@link #listSkills()} / {@link #getSkill(String)}）每次实时读本地文件系统，无缓存。</p>
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);

    /** 沙箱发现允许的子目录深度：{@code /home/gem/skills/<id>/SKILL.md}，深度 2。 */
    private static final int SANDBOX_FIND_MAXDEPTH = 2;

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
        Path skillDir = Path.of(skillRootPath, skillId);
        if (!Files.exists(skillDir)) {
            throw new SkillNotFoundException(skillId);
        }
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            throw new SkillNotFoundException(skillId);
        }
        String content = Files.readString(skillFile);
        return parseSkillMd(skillId, content, skillDir);
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

            for (Path path : Files.list(skillDir).filter(Files::isDirectory).toList()) {
                Path skillFile = path.resolve("SKILL.md");
                if (!Files.exists(skillFile)) {
                    continue;
                }
                try {
                    String content = Files.readString(skillFile);
                    String skillId = path.getFileName().toString();
                    Skill skill = parseSkillMd(skillId, content, path);
                    result.add(skill);
                } catch (IOException e) {
                    log.error("读取技能文件失败: {}", path, e);
                }
            }

            log.info("本地技能仓库扫描完成: {} 个", result.size());
        } catch (IOException e) {
            log.error("扫描本地技能仓库失败: {}", directory, e);
        }
        return result;
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

        // 一次 shell 列出所有 SKILL.md 路径；目录不存在或为空时输出空字符串
        String findCmd = "find " + Skill.SANDBOX_SKILL_ROOT
                + " -maxdepth " + SANDBOX_FIND_MAXDEPTH
                + " -name SKILL.md -type f 2>/dev/null";
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

        List<Skill> result = new ArrayList<>();
        for (String line : output.split("\\r?\\n")) {
            String filePath = line.trim();
            if (filePath.isEmpty() || !filePath.endsWith("/SKILL.md")) {
                continue;
            }
            // 期望路径形如 /home/gem/skills/<id>/SKILL.md
            String prefix = Skill.SANDBOX_SKILL_ROOT + "/";
            if (!filePath.startsWith(prefix)) {
                continue;
            }
            String relative = filePath.substring(prefix.length());
            int slash = relative.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String skillId = relative.substring(0, slash);

            try {
                String content = client.readFile(filePath);
                Skill skill = parseSkillMd(skillId, content, /* localPath= */ null);
                skill.setSource(Skill.Source.SANDBOX);
                result.add(skill);
            } catch (Exception e) {
                log.warn("沙箱发现 skill 解析失败 sessionId={} skillId={}: {}", sessionId, skillId, e.getMessage());
            }
        }
        log.debug("沙箱发现 skill 数量 sessionId={} count={}", sessionId, result.size());
        return result;
    }

    /**
     * 解析 SKILL.md 内容（含 YAML frontmatter）。
     *
     * @param skillId   技能 ID（目录名）
     * @param content   文件全文
     * @param localPath 本地路径；沙箱发现传 null
     * @return 解析得到的技能
     */
    private Skill parseSkillMd(String skillId, String content, Path localPath) {
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

        return new Skill(skillId, name, description, localPath, frontmatterMap);
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
