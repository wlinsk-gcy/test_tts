package com.wlinsk.rd_machine.llm;

import com.wlinsk.rd_machine.config.AiLlmProperties;
import org.springframework.stereotype.Service;

@Service
public class LlmService {

    private final AiLlmProperties properties;

    public LlmService(AiLlmProperties properties) {
        this.properties = properties;
    }

    public String getModel() {
        return properties.getModel();
    }
}
