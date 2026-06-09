package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.Skill;

import java.io.IOException;
import java.util.List;

/**
 * 技能管理服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface SkillService {

    /**
     * 列出所有技能（仅元数据，不加载 content）
     *
     * @return 技能列表
     */
    List<Skill> listSkills();

    /**
     * 获取技能详情（含完整内容）
     *
     * @param skillId 技能 ID
     * @return 技能详情
     * @throws IOException 如果读取文件失败
     */
    Skill getSkill(String skillId) throws IOException;

    /**
     * 从目录加载技能（扫描 SKILL.md 文件）
     *
     * @param directory 目录路径
     */
    void loadSkillsFromDirectory(String directory);

    /**
     * 设置技能根目录
     *
     * @param rootPath 技能根目录路径
     */
    void setSkillRootPath(String rootPath);
}
