package com.example.sandbox.web.repository;

import com.example.sandbox.web.config.AgentConfigProperties;
import com.example.sandbox.web.model.entity.ConversationContextEntity;
import com.example.sandbox.web.model.entity.ConversationSessionEntity;
import com.example.sandbox.web.model.llm.ConversationContextView;
import com.example.sandbox.web.service.LlmService;
import com.example.sandbox.web.service.impl.ConversationContextService;
import com.example.sandbox.web.service.impl.ConversationContextTokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 使用真实 Hibernate 和 H2 验证会话上下文首次创建时的主键映射。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ConversationContextRepositoryJpaTest {

    /** 会话 Repository。 */
    @Autowired
    private ConversationSessionRepository sessionRepository;

    /** 会话上下文 Repository。 */
    @Autowired
    private ConversationContextRepository contextRepository;

    /** Agent 运行账本 Repository。 */
    @Autowired
    private AgentRunRepository agentRunRepository;

    /** 用于清理一级缓存并强制重新读取数据库。 */
    @Autowired
    private EntityManager entityManager;

    /**
     * 会话存在但快照不存在时，首次加载应创建以 sessionId 为主键的上下文记录。
     */
    @Test
    void 首次加载应使用显式会话主键保存上下文() {
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setId("session-jpa-1");
        sessionRepository.saveAndFlush(session);

        AgentConfigProperties properties = new AgentConfigProperties();
        ConversationContextService service = new ConversationContextService(
                contextRepository,
                agentRunRepository,
                sessionRepository,
                new ObjectMapper(),
                mock(LlmService.class),
                new ConversationContextTokenEstimator(properties),
                properties);

        ConversationContextView view = service.load("session-jpa-1");
        entityManager.flush();
        entityManager.clear();

        assertThat(view.summary()).isEmpty();
        assertThat(view.recentMessages()).isEmpty();
        ConversationContextEntity persisted = contextRepository.findById("session-jpa-1").orElseThrow();
        assertThat(persisted.getSessionId()).isEqualTo("session-jpa-1");
    }
}
