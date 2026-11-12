package com.example.fraudcase.application;

import com.example.fraudcase.agent.langchain4j.LangChain4jFollowUpAgent;
import com.example.fraudcase.agent.langchain4j.LangChain4jFraudCaseAgent;
import com.example.fraudcase.agent.langchain4j.LangChain4jModelGate;
import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiFollowUpAgent;
import com.example.fraudcase.agent.springai.SpringAiFraudCaseAgent;
import com.example.fraudcase.agent.springai.SpringAiModelGate;
import com.example.fraudcase.config.CapstoneProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Configuration-driven processor selection and the fail-fast startup
 * contract for AI modes without a configured model. Each AI mode gates on
 * its own provider only — an unselected provider is never required.
 */
class ProcessorSelectorTest {

    private final RulesFraudCaseAgent rules = new RulesFraudCaseAgent();
    private final SpringAiFraudCaseAgent springAi = mock(SpringAiFraudCaseAgent.class);
    private final LangChain4jFraudCaseAgent lc4j = mock(LangChain4jFraudCaseAgent.class);
    private final SpringAiFollowUpAgent springAiFollowUp =
            mock(SpringAiFollowUpAgent.class);
    private final LangChain4jFollowUpAgent lc4jFollowUp =
            mock(LangChain4jFollowUpAgent.class);

    private SpringAiModelGate springAiGate(boolean available) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(
                available ? mock(ChatModel.class) : null);
        return new SpringAiModelGate(provider);
    }

    private LangChain4jModelGate lc4jGate(boolean available) {
        @SuppressWarnings("unchecked")
        ObjectProvider<dev.langchain4j.model.chat.ChatModel> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(
                available ? mock(dev.langchain4j.model.chat.ChatModel.class) : null);
        return new LangChain4jModelGate(provider);
    }

    private ProcessorSelector selector(CapstoneProperties.ProcessorMode mode,
                                       boolean springAiAvailable,
                                       boolean lc4jAvailable) {
        CapstoneProperties p = new CapstoneProperties();
        p.setProcessorMode(mode);
        return new ProcessorSelector(p, rules, springAi, lc4j,
                springAiFollowUp, lc4jFollowUp,
                springAiGate(springAiAvailable), lc4jGate(lc4jAvailable));
    }

    @Test
    void rulesModeSelectsRulesWithoutAnyModel() {
        ProcessorSelector s = selector(
                CapstoneProperties.ProcessorMode.RULES, false, false);
        assertThat(s.processor()).isSameAs(rules);
        assertThat(s.conversationAvailable()).isFalse();
        assertThatThrownBy(s::followUpAgent)
                .isInstanceOf(ConversationUnavailableException.class);
    }

    @Test
    void springAiModeWithModelSelectsAdapterWithoutLc4jModel() {
        ProcessorSelector s = selector(
                CapstoneProperties.ProcessorMode.SPRING_AI, true, false);
        assertThat(s.processor()).isSameAs(springAi);
        assertThat(s.followUpAgent()).isSameAs(springAiFollowUp);
        assertThat(s.conversationAvailable()).isTrue();
    }

    @Test
    void springAiModeWithoutModelFailsFast() {
        assertThatThrownBy(() -> selector(
                CapstoneProperties.ProcessorMode.SPRING_AI, false, false))
                .isInstanceOf(ProcessorModeUnavailableException.class)
                .hasMessageContaining("ChatModel");
    }

    @Test
    void langChain4jModeWithModelSelectsAdapterWithoutSpringAiModel() {
        // The unselected provider must never be required: spring-ai model
        // absent, lc4j present → langchain4j mode still starts.
        ProcessorSelector s = selector(
                CapstoneProperties.ProcessorMode.LANGCHAIN4J, false, true);
        assertThat(s.processor()).isSameAs(lc4j);
        assertThat(s.followUpAgent()).isSameAs(lc4jFollowUp);
        assertThat(s.conversationAvailable()).isTrue();
    }

    @Test
    void langChain4jModeWithoutModelFailsFast() {
        assertThatThrownBy(() -> selector(
                CapstoneProperties.ProcessorMode.LANGCHAIN4J, false, false))
                .isInstanceOf(ProcessorModeUnavailableException.class)
                .hasMessageContaining("langchain4j");
    }

    @Test
    void langChain4jModeIsNotAffectedBySpringAiModelPresence() {
        // Smooth switching: spring-ai configured or not must not change the
        // langchain4j selection outcome.
        assertThat(selector(CapstoneProperties.ProcessorMode.LANGCHAIN4J,
                true, true).processor()).isSameAs(lc4j);
        assertThat(selector(CapstoneProperties.ProcessorMode.LANGCHAIN4J,
                false, true).processor()).isSameAs(lc4j);
    }

    @Test
    void allModesExist() {
        // An unlisted mode can never silently resolve — the enum is closed.
        assertThat(CapstoneProperties.ProcessorMode.values())
                .containsExactlyInAnyOrder(
                        CapstoneProperties.ProcessorMode.RULES,
                        CapstoneProperties.ProcessorMode.SPRING_AI,
                        CapstoneProperties.ProcessorMode.LANGCHAIN4J);
    }

    @Test
    void unknownProcessorModeFailsConfigurationBinding() {
        // "capstone.processor-mode=whatever" cannot bind → startup fails
        // before any processor is selected.
        org.springframework.boot.context.properties.source.MapConfigurationPropertySource source =
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("capstone.processor-mode", "not-a-mode"));
        org.springframework.boot.context.properties.bind.Binder binder =
                new org.springframework.boot.context.properties.bind.Binder(source);
        assertThatThrownBy(() -> binder.bind("capstone.processor-mode",
                CapstoneProperties.ProcessorMode.class))
                .isInstanceOf(
                        org.springframework.boot.context.properties.bind.BindException.class);
    }
}
