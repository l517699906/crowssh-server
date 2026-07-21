package com.llf.ai;

import com.llf.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@Configurable
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService testService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(testService)
                .build();
    }
}
