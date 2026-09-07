package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class QwenAudioSpeechSynthesizerPool implements AutoCloseable {

    private final GenericObjectPool<SpeechSynthesizer> pool;

    public QwenAudioSpeechSynthesizerPool(AiTtsProperties properties) {
        configureEndpoint(properties.getWsUrl());
        AiTtsProperties.Pool settings = properties.getPool();
        if (settings.getMaxTotal() <= 0 || settings.getBorrowTimeoutMs() < 0) {
            throw new BasicException(SysCode.PARAMETER_ERROR.getCode(), "invalid Qwen Audio TTS pool settings");
        }
        GenericObjectPoolConfig<SpeechSynthesizer> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(settings.getMaxTotal());
        config.setMaxIdle(settings.getMaxTotal());
        config.setMinIdle(0);
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(settings.getBorrowTimeoutMs()));
        this.pool = new GenericObjectPool<>(new SpeechSynthesizerFactory(), config);
    }

    SpeechSynthesizer borrow() {
        try {
            return pool.borrowObject();
        } catch (Exception exception) {
            throw new BasicException(SysCode.TTS_STREAM_FAILED.getCode(), "Unable to borrow Qwen Audio TTS connection: " + exception.getMessage());
        }
    }

    void release(SpeechSynthesizer synthesizer) {
        if (synthesizer == null) {
            return;
        }
        try {
            pool.returnObject(synthesizer);
        } catch (Exception exception) {
            log.warn("Failed to return Qwen Audio TTS connection", exception);
            invalidate(synthesizer, "pool return failed");
        }
    }

    void invalidate(SpeechSynthesizer synthesizer, String reason) {
        if (synthesizer == null) {
            return;
        }
        try {
            pool.invalidateObject(synthesizer);
        } catch (Exception exception) {
            log.warn("Failed to invalidate Qwen Audio TTS connection, reason={}", reason, exception);
            closeConnection(synthesizer, reason);
        }
    }

    int maxTotal() {
        return pool.getMaxTotal();
    }

    int maxIdle() {
        return pool.getMaxIdle();
    }

    long maxWaitMillis() {
        return pool.getMaxWaitDuration().toMillis();
    }

    int numIdle() {
        return pool.getNumIdle();
    }

    @Override
    @PreDestroy
    public void close() {
        pool.close();
    }

    private void configureEndpoint(String wsUrl) {
        if (wsUrl == null || wsUrl.isBlank()) {
            throw new BasicException(SysCode.PARAMETER_ERROR.getCode(), "Qwen Audio TTS WebSocket URL is required");
        }
        String normalized = wsUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        Constants.baseWebsocketApiUrl = normalized;
    }

    private static void closeConnection(SpeechSynthesizer synthesizer, String reason) {
        try {
            if (synthesizer.getDuplexApi() != null) {
                synthesizer.getDuplexApi().close(1_000, reason == null ? "invalid" : reason);
            }
        } catch (Exception exception) {
            log.debug("Failed to close Qwen Audio TTS connection, reason={}", reason, exception);
        }
    }

    private static final class SpeechSynthesizerFactory extends BasePooledObjectFactory<SpeechSynthesizer> {

        @Override
        public SpeechSynthesizer create() {
            return new SpeechSynthesizer();
        }

        @Override
        public PooledObject<SpeechSynthesizer> wrap(SpeechSynthesizer synthesizer) {
            return new DefaultPooledObject<>(synthesizer);
        }

        @Override
        public void destroyObject(PooledObject<SpeechSynthesizer> pooledObject) {
            closeConnection(pooledObject.getObject(), "pool destroy");
        }
    }
}
