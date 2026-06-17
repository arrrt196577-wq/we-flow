package org.example.weflow.workflow.chat;

import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.agent.subagent.SubAgentRegistry;
import org.example.weflow.core.agent.AgentDefinition;
import org.example.weflow.core.agent.AgentExecutor;
import org.example.weflow.core.agent.AgentSpec;
import org.example.weflow.workflow.agent.AgentGraphFactory;
import org.example.weflow.workflow.agent.DefaultAgentSpecs;
import org.example.weflow.workflow.agent.GraphBackedAgentExecutor;
import org.example.weflow.workflow.agent.ImplementPlaceholderAgentExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "we-flow.chat", name = "engine", havingValue = "langgraph4j")
class LangGraph4jChatHarnessConfiguration {

    @Bean
    AgentGraphFactory agentGraphFactory(StreamingChatModel streamingChatModel, LC4jToolService toolService) {
        return new AgentGraphFactory(streamingChatModel, toolService);
    }

    @Bean("leadAgentSpec")
    AgentSpec leadAgentSpec(ObjectProvider<SubAgentRegistry> subAgentRegistryProvider, LC4jToolService toolService) {
        SubAgentRegistry subAgentRegistry = subAgentRegistryProvider.getIfAvailable();
        List<AgentDefinition> subAgentDefinitions = subAgentRegistry == null
                ? List.of()
                : subAgentRegistry.listDefinitions();
        return DefaultAgentSpecs.leadAgentSpec(subAgentDefinitions, toolNames(toolService));
    }

    @Bean("searchAgentSpec")
    AgentSpec searchAgentSpec() {
        return DefaultAgentSpecs.searchAgentSpec();
    }

    @Bean
    AgentExecutor searchAgentExecutor(
            @Qualifier("searchAgentSpec") AgentSpec searchAgentSpec,
            ObjectProvider<AgentGraphFactory> graphFactoryProvider
    ) {
        return new GraphBackedAgentExecutor(searchAgentSpec, graphFactoryProvider);
    }

    @Bean
    AgentExecutor implementAgentExecutor() {
        return new ImplementPlaceholderAgentExecutor();
    }

    private Set<String> toolNames(LC4jToolService toolService) {
        return toolService.toolSpecifications().stream()
                .map(specification -> specification.name())
                .collect(Collectors.toUnmodifiableSet());
    }
}
