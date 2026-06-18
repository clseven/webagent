package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.Skill;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话记忆服务接口
 *
 * @author example
 * @date 2026/05/14
 */
public interface ConversationService {

    /**
     * 添加用户消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addUserMessage(String sessionId, String content);

    /**
     * 添加助手消息
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     */
    void addAssistantMessage(String sessionId, String content);

    /**
     * 添加助手消息（带思考链）
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param reasoning 思考链内容（可为 null）
     */
    void addAssistantMessage(String sessionId, String content, String reasoning);

    /**
     * 添加助手消息，并保存用于前端恢复展示的执行过程事件。
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @param reasoning 思考链内容，可为 null
     * @param events    assistant 消息对应的 plan、thinking、toolResult 等展示事件，可为空
     */
    void addAssistantMessage(String sessionId, String content, String reasoning, List<Map<String, Object>> events);

    /**
     * 获取消息历史
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<ChatMessage> getHistory(String sessionId);

    /**
     * 清空消息历史
     *
     * @param sessionId 会话 ID
     */
    void clearHistory(String sessionId);

    /**
     * 删除会话及其关联消息和启用技能记录。
     *
     * @param sessionId 会话 ID
     */
    void deleteSession(String sessionId);

    /**
     * 构建系统提示（仅技能元数据，不含消息历史）
     *
     * @param sessionId 会话 ID
     * @return 系统提示字符串
     */
    String buildSystemPrompt(String sessionId);

    /**
     * 获取最近 N 条消息历史（供 ReAct 循环构建固定前缀）
     *
     * @param sessionId 会话 ID
     * @param limit     最大条数
     * @return 消息列表
     */
    List<ChatMessage> getRecentHistory(String sessionId, int limit);

    /**
     * 启用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void enableSkill(String sessionId, String skillId);

    /**
     * 禁用技能
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     */
    void disableSkill(String sessionId, String skillId);

    /**
     * 获取启用的技能 ID
     *
     * @param sessionId 会话 ID
     * @return 技能 ID 集合
     */
    Set<String> getEnabledSkillIds(String sessionId);

    /**
     * 获取启用的技能列表
     *
     * @param sessionId 会话 ID
     * @return 技能列表
     */
    List<Skill> getEnabledSkills(String sessionId);

    /**
     * 获取技能内容
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @return 技能内容
     */
    String getSkillContent(String sessionId, String skillId);

    /**
     * 获取技能引用文件内容
     *
     * @param sessionId 会话 ID
     * @param skillId   技能 ID
     * @param path      引用文件路径
     * @return 文件内容
     */
    String getSkillReference(String sessionId, String skillId, String path);
}
