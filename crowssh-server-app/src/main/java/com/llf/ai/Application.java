package com.llf.ai;

import com.llf.ai.domain.agent.service.armory.matter.mcp.server.MyTestMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Configurable
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class);
    }

    /** 测试工具：大小写转换 */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService toolService) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(toolService)
                .build();
    }
}
