package com.example.sandbox.web.service.impl;

import com.example.sandbox.web.exception.SkillNotFoundException;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能管理服务实现（不存数据库，直接读本地文件系统）
 *
 * @author example
 * @date 2026/05/14
 */
@Service
public class SkillServiceImpl implements SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillServiceImpl.class);

    /**
     * 技能缓存：id -> Skill
     */
    private final Map<String, Skill> skillCache = new ConcurrentHashMap<>();

    /**
     * 技能根目录
     */
    private Path skillRootPath;

    /**
     * 启动时不自动加载，等待用户设置根目录
     */
    // @jakarta.annotation.PostConstruct
    // public void init() {
    //     String defaultPath = ".claude/skills";
    //     log.info("初始化技能服务，默认技能目录: {}", defaultPath);
    //     setSkillRootPath(defaultPath);
    // }

    @Override
    public List<Skill> listSkills() {
        return new ArrayList<>(skillCache.values());
    }

    @Override
    public Skill getSkill(String skillId) throws IOException {
        Skill skill = skillCache.get(skillId);
        if (skill == null) {
            log.warn("技能 {} 未找到（缓存中无此技能）", skillId);
            throw new SkillNotFoundException(skillId);
        }
        // 触发一次 content 加载以验证文件存在
        String content = skill.getContent();
        log.info("已获取技能: {} (内容长度: {} 字符)", skillId, content.length());
        return skill;
    }

    @Override
    public void loadSkillsFromDirectory(String directory) {
        log.info("开始加载技能目录: {}", directory);
        try {
            Path skillDir = Path.of(directory);
            if (!Files.exists(skillDir)) {
                log.warn("技能目录不存在: {}", directory);
                return;
            }

            int loadedCount = 0;
            List<String> loadedSkillIds = new ArrayList<>();
            for (Path path : Files.list(skillDir).filter(Files::isDirectory).toList()) {
                if (loadSkillFromPath(path)) {
                    loadedCount++;
                    loadedSkillIds.add(path.getFileName().toString());
                }
            }

            log.info("技能加载完成: {} 个 - {}", loadedCount, loadedSkillIds);
        } catch (IOException e) {
            log.error("加载技能目录失败: {}", directory, e);
        }
    }

    @Override
    public void setSkillRootPath(String rootPath) {
        this.skillRootPath = Path.of(rootPath);
        loadSkillsFromDirectory(rootPath);
    }

    /**
     * 从路径加载单个技能
     *
     * @return true 如果加载成功
     */
    private boolean loadSkillFromPath(Path skillPath) {
        Path skillFile = skillPath.resolve("SKILL.md");
        if (!Files.exists(skillFile)) {
            log.debug("No SKILL.md found in {}", skillPath);
            return false;
        }

        try {
            String content = Files.readString(skillFile);
            String skillId = skillPath.getFileName().toString();
            Skill skill = parseSkillMd(skillId, content, skillPath);

            skillCache.put(skillId, skill);
            log.debug("Loaded skill: {} from {}", skill.getId(), skillPath);
            return true;
        } catch (IOException e) {
            log.error("Failed to load skill from: {}", skillPath, e);
            return false;
        }
    }

    /**
     * 解析 SKILL.md 内容
     */
    private Skill parseSkillMd(String skillId, String content, Path skillPath) {
        // 解析 YAML frontmatter: ---
        // name: xxx
        // description: xxx
        // ---
        String name = "";
        String description = "";

        // 尝试解析 frontmatter
        if (content.startsWith("---")) {
            int endIndex = content.indexOf("---", 3);
            if (endIndex > 0) {
                String frontmatter = content.substring(3, endIndex);
                name = extractFieldFromFrontmatter(frontmatter, "name");
                description = extractFieldFromFrontmatter(frontmatter, "description");
            }
        }

        // 如果 frontmatter 没有 description，尝试从内容提取
        if (description.isEmpty()) {
            description = extractDescriptionFromContent(content);
        }

        return new Skill(skillId, name, description, skillPath);
    }

    /**
     * 从 YAML frontmatter 提取指定字段
     */
    private String extractFieldFromFrontmatter(String frontmatter, String fieldName) {
        String prefix = fieldName + ":";
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                // 去掉引号包裹
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return "";
    }

    /**
     * 从 Markdown 内容提取描述（标题后的第一段）
     */
    private String extractDescriptionFromContent(String content) {
        // 跳过 frontmatter
        String body = content;
        if (content.startsWith("---")) {
            int endIndex = content.indexOf("---", 3);
            if (endIndex > 0) {
                body = content.substring(endIndex + 3).trim();
            }
        }

        // 提取第一个非空行作为描述
        for (String line : body.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                // 取第一句话（以句号结尾或整行）
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
