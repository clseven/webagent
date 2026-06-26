package com.example.sandbox.web.model.entity;

import com.example.sandbox.aio.AioClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 技能元数据。
 *
 * <p>技能可来自两个源：</p>
 * <ul>
 *   <li><b>local</b> — 用户在前端配置的本地仓库（{@code SkillService.setSkillRootPath}），是会话沙箱的种子来源；
 *   {@link #localPath} 持有本地目录路径，用于 {@code FileSyncService.syncSkill} 推送。</li>
 *   <li><b>sandbox</b> — 当前会话沙箱 {@code /home/gem/skills/} 下扫描到的技能，可能是 Agent 自行下载或生成；
 *   {@link #localPath} 为 null，所有 IO 走 {@link AioClient}。</li>
 * </ul>
 *
 * <p>三级渐进式披露：</p>
 * <ol>
 *   <li>元数据层：id + description（简历模式，约 30-50 token）</li>
 *   <li>详细层：SKILL.md 完整内容（按需加载，运行期一律走沙箱）</li>
 *   <li>资源层：references/、scripts/ 下的辅助文件（用到才取，运行期一律走沙箱）</li>
 * </ol>
 *
 * @author example
 * @date 2026/05/14
 */
public class Skill {

    /** 沙箱内技能根目录，所有运行期 skill 文件统一住在 {@code /home/gem/skills/<id>/}。 */
    public static final String SANDBOX_SKILL_ROOT = "/home/gem/skills";

    /** 技能来源枚举：本地仓库、当前会话沙箱、两者都有。 */
    public enum Source {
        /** 仅在本地仓库（前端配置的根目录），未推送到沙箱。 */
        LOCAL,
        /** 仅在当前会话沙箱内（Agent 自行下载或生成），本地仓库没有。 */
        SANDBOX,
        /** 本地仓库和沙箱中都存在（已启用并已同步）。 */
        BOTH
    }

    /** 技能唯一标识（目录名）。 */
    private final String id;

    /** 名称（从 frontmatter 的 name 字段提取，缺省回退到 id）。 */
    private final String name;

    /** 描述（展示用，让 agent 判断是否相关）。 */
    private final String description;

    /**
     * 本地仓库目录路径。
     *
     * <p>仅当技能存在于用户本地仓库时非空；沙箱独有的技能（{@link Source#SANDBOX}）此字段为 null。
     * 仅用于 {@code FileSyncService.syncSkill} 推送种子文件到沙箱，不用于运行期读取。</p>
     */
    private final Path localPath;

    /** 完整的 YAML frontmatter（所有字段，含 metadata、license 等）。 */
    private final Map<String, Object> frontmatter;

    /** 技能来源标记，由 {@code SkillService} 在融合本地仓库视图和沙箱视图时打上。 */
    private Source source;

    /**
     * 构造本地仓库技能（来源默认为 {@link Source#LOCAL}，可被 {@link #setSource(Source)} 修正为 {@link Source#BOTH}）。
     *
     * @param id          技能 ID（目录名）
     * @param name        从 frontmatter 解析的 name；为空时回退到 id
     * @param description 从 frontmatter 解析的 description
     * @param localPath   本地仓库目录路径，可为 null（沙箱独有的技能）
     * @param frontmatter 完整 frontmatter Map，可为 null
     */
    public Skill(String id, String name, String description, Path localPath, Map<String, Object> frontmatter) {
        this.id = id;
        this.name = (name != null && !name.isBlank()) ? name : id;
        this.description = description;
        this.localPath = localPath;
        this.frontmatter = frontmatter != null ? Collections.unmodifiableMap(frontmatter) : Collections.emptyMap();
        this.source = (localPath != null) ? Source.LOCAL : Source.SANDBOX;
    }

    /**
     * 生成第一层元数据（简历模式）。
     * 格式：- {@code <id>: <description>}，约 30-50 token per skill。
     *
     * @return 单行 markdown 列表项
     */
    public String toMetadataLine() {
        return String.format("- %s: %s", id, description);
    }

    // ==================== 本地 IO（仅用于 seed 推送 / 启动时的元数据解析） ====================

    /**
     * 从本地仓库读取 SKILL.md 完整内容。
     *
     * <p>仅当 {@link #localPath} 非空时可用；运行期读取请改用 {@link #getContent(AioClient)} 走沙箱。</p>
     *
     * @return SKILL.md 全文
     * @throws IOException        文件不存在或读取失败
     * @throws IllegalStateException 当前技能没有本地副本（沙箱独有）
     */
    public String getContent() throws IOException {
        requireLocalPath();
        return Files.readString(getSkillFile());
    }

    /**
     * 从本地仓库读取引用文件。仅用于本地兜底；运行期使用 {@link #getReferenceFile(AioClient, String)}。
     *
     * @param relativePath 相对于 skill 目录的路径，如 "references/anti-patterns.md"
     * @return 文件内容
     * @throws IOException 路径非法或文件不存在
     */
    public String getReferenceFile(String relativePath) throws IOException {
        requireLocalPath();
        Path normalizedBase = localPath.normalize();
        Path file = normalizedBase.resolve(relativePath).normalize();
        if (!file.startsWith(normalizedBase)) {
            throw new IOException("Invalid reference path: " + relativePath);
        }
        if (!Files.exists(file)) {
            throw new IOException("Reference file not found: " + relativePath);
        }
        return Files.readString(file);
    }

    // ==================== 沙箱 IO（运行期唯一权威数据源） ====================

    /**
     * 从当前会话沙箱读取 SKILL.md 完整内容。
     *
     * @param client 当前会话的 AIO 客户端
     * @return SKILL.md 全文
     */
    public String getContent(AioClient client) {
        return client.readFile(sandboxSkillFilePath());
    }

    /**
     * 从当前会话沙箱读取引用文件。
     *
     * <p>路径校验：{@code relativePath} 不允许包含 {@code ..} 或绝对路径前缀，确保读取范围限定在
     * 沙箱 {@code /home/gem/skills/<id>/} 内。</p>
     *
     * @param client       当前会话的 AIO 客户端
     * @param relativePath 相对于 skill 目录的路径，如 "references/anti-patterns.md"
     * @return 文件内容
     * @throws IllegalArgumentException 路径非法
     */
    public String getReferenceFile(AioClient client, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Reference path cannot be blank");
        }
        // 拒绝 ../ 越界和绝对路径
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            throw new IllegalArgumentException("Invalid reference path: " + relativePath);
        }
        return client.readFile(sandboxBasePath() + "/" + normalized);
    }

    /**
     * 列出当前沙箱中此技能的 scripts/ 目录下所有可执行脚本。
     *
     * <p>用 shell {@code find} 直接列文件名，避免依赖 AIO file 的 list 响应结构。</p>
     *
     * @param client 当前会话的 AIO 客户端
     * @return 相对路径列表，如 ["scripts/run.sh", "scripts/clean.py"]
     */
    public List<String> listScripts(AioClient client) {
        return listSandboxFiles(client, "scripts");
    }

    /**
     * 列出当前沙箱中此技能的 references/ 目录下所有引用文件。
     *
     * @param client 当前会话的 AIO 客户端
     * @return 相对路径列表，如 ["references/anti-patterns.md"]
     */
    public List<String> listReferences(AioClient client) {
        return listSandboxFiles(client, "references");
    }

    /**
     * 用单次 shell {@code find} 命令列出沙箱内子目录里的常规文件。
     *
     * @param client    AIO 客户端
     * @param subDir    子目录名，如 "scripts" 或 "references"
     * @return 形如 {@code <subDir>/<filename>} 的相对路径列表；目录不存在或为空时返回空列表
     */
    private List<String> listSandboxFiles(AioClient client, String subDir) {
        String dir = sandboxBasePath() + "/" + subDir;
        // -maxdepth 1 防止下沉到任意层；2>/dev/null 在目录不存在时返回空输出，不抛错
        String cmd = "find " + shellQuote(dir) + " -maxdepth 1 -type f 2>/dev/null";
        String output;
        try {
            output = client.execCommand(cmd);
        } catch (Exception e) {
            return List.of();
        }
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        String base = sandboxBasePath() + "/";
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith(base)) {
                result.add(trimmed.substring(base.length()));
            } else if (trimmed.startsWith(dir + "/")) {
                // 安全网：直接以 dir 开头
                result.add(subDir + "/" + trimmed.substring(dir.length() + 1));
            }
        }
        return result;
    }

    /**
     * 沙箱内 skill 根目录，例如 {@code /home/gem/skills/brainstorming}。
     *
     * @return 沙箱目录绝对路径
     */
    public String sandboxBasePath() {
        return SANDBOX_SKILL_ROOT + "/" + id;
    }

    /**
     * 沙箱内 SKILL.md 路径，例如 {@code /home/gem/skills/brainstorming/SKILL.md}。
     *
     * @return 沙箱 SKILL.md 绝对路径
     */
    public String sandboxSkillFilePath() {
        return sandboxBasePath() + "/SKILL.md";
    }

    /**
     * 把字符串包装成单引号 shell 参数，转义内嵌单引号。
     *
     * @param value 原始路径
     * @return 安全的 shell 参数
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * 校验本地副本存在，避免在沙箱独有的技能上误调本地 IO。
     */
    private void requireLocalPath() throws IOException {
        if (localPath == null) {
            throw new IOException("Skill " + id + " has no local copy; use sandbox IO instead");
        }
    }

    // ==================== 列出/检查辅助（保留兼容，不变更签名） ====================

    /**
     * 兼容旧调用：列出本地仓库 references 目录下的文件路径。
     *
     * <p>仅在 {@link #localPath} 非空时可用；运行期请改用 {@link #listReferences(AioClient)}。</p>
     *
     * @return 本地引用文件 Path 列表
     * @throws IOException 列目录失败
     */
    public List<Path> listLocalReferences() throws IOException {
        if (localPath == null) {
            return List.of();
        }
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

    /**
     * 生成简短文本，列出本地仓库中可见的引用文件（兼容老调用，不再被推荐使用）。
     *
     * @return 引用文件清单文本，无文件时返回空串
     * @throws IOException 列目录失败
     */
    public String listAvailableReferences() throws IOException {
        List<Path> refs = listLocalReferences();
        if (refs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Available references:\n");
        for (Path ref : refs) {
            sb.append("  - ").append(localPath.relativize(ref).toString().replace("\\", "/")).append("\n");
        }
        return sb.toString();
    }

    /** @return 本地 SKILL.md 文件路径（沙箱独有时返回 null 不会被检查；调用方需保证有本地副本） */
    public Path getSkillFile() {
        return localPath != null ? localPath.resolve("SKILL.md") : null;
    }

    /** @return 本地 scripts 目录路径，沙箱独有时为 null */
    public Path getScriptsDir() {
        return localPath != null ? localPath.resolve("scripts") : null;
    }

    /** @return 本地 references 目录路径，沙箱独有时为 null */
    public Path getReferencesDir() {
        return localPath != null ? localPath.resolve("references") : null;
    }

    /** @return 是否在本地仓库中存在 scripts 目录 */
    public boolean hasScripts() {
        Path dir = getScriptsDir();
        return dir != null && Files.exists(dir) && Files.isDirectory(dir);
    }

    // ==================== Getters / 来源标记 ====================

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 本地仓库目录路径。
     *
     * @return 本地路径；沙箱独有的技能返回 null
     */
    public Path getLocalPath() {
        return localPath;
    }

    public Map<String, Object> getFrontmatter() {
        return frontmatter;
    }

    /** @return 当前技能来源标记，由 {@code SkillService} 在融合视图时打上 */
    public Source getSource() {
        return source;
    }

    /**
     * 标记技能来源（{@code SkillService} 在融合本地仓库与沙箱视图时调用）。
     *
     * @param source 来源
     */
    public void setSource(Source source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "Skill{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", source=" + source +
                ", localPath=" + localPath +
                '}';
    }
}
