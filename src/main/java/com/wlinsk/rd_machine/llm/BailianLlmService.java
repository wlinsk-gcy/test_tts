package com.wlinsk.rd_machine.llm;

import com.wlinsk.rd_machine.config.AiLlmProperties;
import org.springframework.stereotype.Service;

@Service
public class BailianLlmService {

    private final AiLlmProperties properties;

    public BailianLlmService(AiLlmProperties properties) {
        this.properties = properties;
    }

    public String getModel() {
        return properties.getModel();
    }
}
