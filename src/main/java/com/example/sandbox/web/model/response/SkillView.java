package com.example.sandbox.web.model.response;

import com.example.sandbox.web.model.entity.Skill;

/**
 * 前端 Skills 页面用的融合视图项。
 *
 * <p>本地仓库与当前会话沙箱发现的技能合并后返回，每行携带来源标签与启用状态。</p>
 */
public class SkillView {

    /** 技能 ID（目录名）。 */
    private String id;

    /** 技能名称（frontmatter.name，缺省回退到 id）。 */
    private String name;

    /** 技能描述。 */
    private String description;

    /**
     * 来源标记：{@code local}（仅本地仓库）/ {@code sandbox}（仅沙箱）/ {@code both}（两者都有）。
     */
    private String source;

    /** 是否在当前会话已启用。 */
    private boolean enabled;

    public SkillView() {
    }

    /**
     * 从 {@link Skill} 实体构建视图。
     *
     * @param skill   技能
     * @param source  最终来源标记
     * @param enabled 是否启用
     * @return 视图实例
     */
    public static SkillView from(Skill skill, Skill.Source source, boolean enabled) {
        SkillView v = new SkillView();
        v.id = skill.getId();
        v.name = skill.getName();
        v.description = skill.getDescription();
        v.source = source.name().toLowerCase();
        v.enabled = enabled;
        return v;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
