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
     * Spring注入所有实现了AgnetTool接口的Bean
     * @param tools
     * @return
     */
    @Bean
    @ConditionalOnMissingBean
    public LC4jToolService lc4jToolService(List<AgentTool> tools) {
        LC4jToolService.Builder builder = LC4jToolService.builder();
        // 扫描工具对象带@Tool注解的方法，提取工具名、描述、参数schema
        tools.forEach(builder::toolsFromObject);
        return builder.build();
    }
}
