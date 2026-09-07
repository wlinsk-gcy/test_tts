package com.wlinsk.rd_machine.config;

import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AiTtsPropertiesTest {

    @Test
    void removesOldQwen3ProtocolProperties() {
        assertThrows(NoSuchFieldException.class, () -> AiTtsProperties.class.getDeclaredField("mode"));
        assertThrows(NoSuchFieldException.class, () -> AiTtsProperties.class.getDeclaredField("commit"));
        assertThrows(NoSuchFieldException.class, () -> AiTtsProperties.class.getDeclaredField("debugLogUpstreamEvents"));
    }

    @Test
    void removesLocalTextSegmentationConfiguration() {
        assertThrows(NoSuchFieldException.class, () -> AiTtsProperties.class.getDeclaredField("segment"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.wlinsk.rd_machine.basic.config.AiTtsProperties$Segment"));
    }
}
