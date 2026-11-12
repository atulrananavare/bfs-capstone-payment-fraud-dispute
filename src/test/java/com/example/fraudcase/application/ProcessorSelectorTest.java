package com.example.fraudcase.application;

import com.example.fraudcase.agent.rules.RulesFraudCaseAgent;
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
 * contract for spring-ai mode without a configured model.
 */
class ProcessorSelectorTest {

    private final RulesFraudCaseAgent rules = new RulesFraudCaseAgent();
    private final SpringAiFraudCaseAgent ai = mock(SpringAiFraudCaseAgent.class);

    private SpringAiModelGate gate(boolean available) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(
                available ? mock(ChatModel.class) : null);
        return new SpringAiModelGate(provider);
    }

    private CapstoneProperties props(CapstoneProperties.ProcessorMode mode) {
        CapstoneProperties p = new CapstoneProperties();
        p.setProcessorMode(mode);
        return p;
    }

    @Test
    void rulesModeSelectsRulesWithoutAnyModel() {
        ProcessorSelector selector = new ProcessorSelector(
                props(CapstoneProperties.ProcessorMode.RULES), rules, ai, gate(false));
        assertThat(selector.processor()).isSameAs(rules);
    }

    @Test
    void springAiModeWithModelSelectsAdapter() {
        ProcessorSelector selector = new ProcessorSelector(
                props(CapstoneProperties.ProcessorMode.SPRING_AI), rules, ai, gate(true));
        assertThat(selector.processor()).isSameAs(ai);
    }

    @Test
    void springAiModeWithoutModelFailsFast() {
        assertThatThrownBy(() -> new ProcessorSelector(
                props(CapstoneProperties.ProcessorMode.SPRING_AI), rules, ai, gate(false)))
                .isInstanceOf(ProcessorModeUnavailableException.class)
                .hasMessageContaining("ChatModel");
    }

    @Test
    void onlyRulesAndSpringAiModesExist() {
        // An unlisted mode can never silently resolve — the enum is closed.
        assertThat(CapstoneProperties.ProcessorMode.values())
                .containsExactlyInAnyOrder(
                        CapstoneProperties.ProcessorMode.RULES,
                        CapstoneProperties.ProcessorMode.SPRING_AI);
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
