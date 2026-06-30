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
     * 列出本地仓库中的技能上传源（实时扫描文件系统，无缓存）。
     *
     * <p>本地仓库只用于把用户选择的技能目录同步到沙箱，不作为运行时读取和使用 skill 的数据源。</p>
     *
     * @return 本地上传源列表
     */
    List<Skill> listSkills();

    /**
     * 从本地仓库定位单个技能上传源（实时读文件系统，无缓存）。
     *
     * <p>调用方只能用返回的本地路径做同步上传；运行时激活、引用文件读取必须使用沙箱发现结果。</p>
     *
     * @param skillId 技能 ID
     * @return 本地上传源元数据
     * @throws IOException 如果读取文件失败
     */
    Skill getSkill(String skillId) throws IOException;

    /**
     * 从目录加载技能上传源（递归扫描 SKILL.md/skill.md 文件），直接返回结果不写缓存。
     *
     * @param directory 目录路径
     * @return 加载到的本地上传源列表
     */
    List<Skill> loadSkillsFromDirectory(String directory);

    /**
     * 设置技能根目录（仅保存路径，不做缓存预热）。
     *
     * @param rootPath 技能根目录路径
     */
    void setSkillRootPath(String rootPath);

    /**
     * 扫描指定会话的沙箱 {@code /home/gem/skills/} 目录，发现其中存在的所有 skill。
     *
     * <p>这是运行期的权威发现来源：本地仓库通过 {@code syncSkill} 推送过去的种子、Agent 自行
     * 下载或生成的 skill 都会出现在这里。返回的 {@link Skill} 实例的 {@code localPath} 为 null，
     * 所有 IO 必须通过沙箱 {@link com.example.sandbox.aio.AioClient}。</p>
     *
     * <p>本方法会执行单次 shell {@code find} + 多次 file/read，未做缓存；调用方按需触发。</p>
     *
     * @param sessionId 会话 ID
     * @return 沙箱内发现的 skill 列表；沙箱未就绪或目录不存在时返回空列表
     */
    List<Skill> discoverFromSandbox(String sessionId);
}
