package com.example.sandbox.web.service;

import com.example.sandbox.web.model.entity.ChatMessage;
import com.example.sandbox.web.model.entity.Skill;
import com.example.sandbox.web.model.llm.AgentContinuation;
import com.example.sandbox.web.model.llm.AgentRunStatus;
import com.example.sandbox.web.model.response.SkillView;

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
     * 添加带运行状态和协议检查点的助手消息。
     *
     * @param sessionId         会话 ID
     * @param content           面向用户展示的助手正文
     * @param reasoning         最终思考链，可为 null
     * @param events            前端过程展示事件
     * @param runStatus         Agent 运行状态
     * @param checkpointMessages 暂停时的完整模型协议消息；正常完成时为空
     */
    void addAssistantMessage(String sessionId, String content, String reasoning,
                             List<Map<String, Object>> events, AgentRunStatus runStatus,
                             List<ChatMessage> checkpointMessages);

    /**
     * 获取会话最后一次可继续的暂停运行。
     *
     * <p>新数据优先恢复协议检查点；旧超限消息从展示事件生成文本续接资料。</p>
     *
     * @param sessionId 会话 ID
     * @return 可用的续接资料；没有暂停运行时返回空对象
     */
    AgentContinuation getLatestContinuation(String sessionId);

    /**
     * 更新自动生成的会话标题。
     *
     * <p>该方法只应覆盖空标题或默认标题，避免后台标题生成任务误覆盖用户已有标题。</p>
     *
     * @param sessionId 会话 ID
     * @param title     生成后的标题；为空时不更新
     */
    void updateGeneratedTitle(String sessionId, String title);

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
     * 在同一事务中删除指定用户拥有的多个会话。
     *
     * <p>不存在或不属于该用户的会话不会删除，也不会抛出权限差异信息。</p>
     *
     * @param sessionIds 待删除的会话 ID 集合
     * @param userId     会话所属用户 ID
     * @return 实际成功删除的会话 ID
     */
    List<String> deleteSessionsOwnedByUser(Set<String> sessionIds, Long userId);

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

    /**
     * 列出当前会话可见的所有技能（融合本地仓库与沙箱发现）。
     *
     * <p>每项标注来源（local / sandbox / both）与是否启用。前端 Skills 页面使用此接口刷新列表。</p>
     *
     * @param sessionId 会话 ID
     * @return 融合视图列表，按 id 排序
     */
    List<SkillView> listSessionSkills(String sessionId);
}
