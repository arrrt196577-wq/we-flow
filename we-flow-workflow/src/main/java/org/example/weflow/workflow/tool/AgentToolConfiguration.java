package org.example.weflow.workflow.tool;

import java.util.List;
import org.bsc.langgraph4j.langchain4j.tool.LC4jToolService;
import org.example.weflow.agent.tool.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentToolConfiguration {

    /**
     * Registers every Spring bean marked as an agent tool with LangChain4j.
     */
    @Bean
    @ConditionalOnMissingBean
    public LC4jToolService lc4jToolService(List<AgentTool> tools) {
        LC4jToolService.Builder builder = LC4jToolService.builder();
        // Scan @Tool methods and extract tool names, descriptions, and parameter schemas.
        tools.forEach(builder::toolsFromObject);
        return builder.build();
    }
}
