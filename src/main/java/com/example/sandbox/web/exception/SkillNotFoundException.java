package com.example.sandbox.web.exception;

/**
 * 技能不存在异常
 *
 * @author example
 * @date 2026/05/14
 */
public class SkillNotFoundException extends RuntimeException {

    private final String skillId;

    public SkillNotFoundException(String skillId) {
        super("Skill not found: " + skillId);
        this.skillId = skillId;
    }

    public String getSkillId() {
        return skillId;
    }
}