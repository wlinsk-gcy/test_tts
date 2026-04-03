package com.wlinsk.rd_machine.utils.zh_convert;

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
        CustomZhConvertUtil.toSimple(WARMUP_TEXT);
        log.info("opencc warmup completed elapsedMs={}", (Math.max(0L, (System.nanoTime() - start) / 1_000_000L)));
    }
}
