package com.wlinsk.rd_machine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rd.ai.llm")
public class AiLlmProperties {

    private String apiKey;
    private String baseUrl;
    private String model;
}
