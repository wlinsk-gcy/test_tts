package com.wlinsk.rd_machine.tts;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenccWarmup implements ApplicationRunner {

    static final String WARMUP_TEXT = "文中的「我」見到閏土時，";

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        warmup();
        long elapsedMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
        log.info("opencc warmup completed elapsedMs={}", elapsedMs);
    }

    String warmup() {
        return ZhConverterUtil.toSimple(WARMUP_TEXT);
    }
}
