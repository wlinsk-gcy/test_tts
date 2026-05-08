package com.wlinsk.rd_machine.core.tts.cosyvoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wlinsk.rd_machine.basic.config.CosyVoiceTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.utils.snowflake.IdUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class CosyVoiceConnectionPool {

    public interface ClientFactory {

        CosyVoiceWebSocketClient create(String connectionId);
    }

    private final CosyVoiceTtsProperties properties;
    private final LongSupplier nanoTime;
    private final ClientFactory clientFactory;
    private final Deque<CosyVoiceWebSocketClient> idleConnections = new ArrayDeque<>();
    private int totalConnections;

    @Autowired
    public CosyVoiceConnectionPool(CosyVoiceTtsProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                System::nanoTime,
                connectionId -> new CosyVoiceWebSocketClient(
                        connectionId,
                        properties,
                        objectMapper,
                        new CosyVoiceWebSocketClient.JdkConnector(Duration.ofMillis(properties.getConnectTimeoutMs()))
                )
        );
    }

    public CosyVoiceConnectionPool(
            CosyVoiceTtsProperties properties,
            LongSupplier nanoTime,
            ClientFactory clientFactory
    ) {
        this.properties = properties;
        this.nanoTime = nanoTime;
        this.clientFactory = clientFactory;
    }

    public CosyVoiceWebSocketClient acquire() {
        long waitDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getPool().getAcquireTimeoutMs());
        while (true) {
            synchronized (this) {
                cleanupExpiredIdleConnections();
                CosyVoiceWebSocketClient reusable = pollReusable();
                if (reusable != null) {
                    log.info(
                            "cosyvoice.connection.reused connectionId={} idleMs={}",
                            reusable.connectionId(),
                            idleMs(reusable)
                    );
                    return reusable;
                }
                if (totalConnections < properties.getPool().getMaxSize()) {
                    totalConnections += 1;
                    break;
                }
                long remainingNs = waitDeadlineNs - System.nanoTime();
                if (remainingNs <= 0) {
                    log.error("{}, currentConnections: {}", SysCode.TTS_SESSION_LIMIT_REACHED.getMessage(), totalConnections);
                    throw new BasicException(SysCode.TTS_SESSION_LIMIT_REACHED);
                }
                try {
                    wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.warn("occur InterruptedException: ",exception);
                    throw new BasicException(SysCode.TTS_STREAM_FAILED);
                }
            }
        }

        CosyVoiceWebSocketClient client = clientFactory.create(IdUtils.build("cosyvoice-conn-"));
        try {
            client.connect().join();
            return client;
        } catch (Exception exception) {
            synchronized (this) {
                totalConnections = Math.max(0, totalConnections - 1);
                notifyAll();
            }
            throw new BasicException(SysCode.TTS_STREAM_FAILED);
        }
    }

    public void releaseReusable(CosyVoiceWebSocketClient client) {
        if (client == null) {
            return;
        }
        synchronized (this) {
            if (client.isOpen() && !isExpired(client)) {
                idleConnections.addLast(client);
                notifyAll();
                log.info("cosyvoice.connection.released connectionId={}", client.connectionId());
                return;
            }
        }
        discard(client, "not reusable");
    }

    public void discard(CosyVoiceWebSocketClient client, String reason) {
        if (client == null) {
            return;
        }
        client.close(reason);
        synchronized (this) {
            idleConnections.remove(client);
            totalConnections = Math.max(0, totalConnections - 1);
            notifyAll();
        }
    }

    synchronized int totalConnections() {
        return totalConnections;
    }

    private CosyVoiceWebSocketClient pollReusable() {
        while (!idleConnections.isEmpty()) {
            CosyVoiceWebSocketClient client = idleConnections.removeFirst();
            if (client.isOpen() && !isExpired(client)) {
                return client;
            }
            closeExpired(client);
        }
        return null;
    }

    private void cleanupExpiredIdleConnections() {
        Iterator<CosyVoiceWebSocketClient> iterator = idleConnections.iterator();
        while (iterator.hasNext()) {
            CosyVoiceWebSocketClient client = iterator.next();
            if (!client.isOpen() || isExpired(client)) {
                iterator.remove();
                closeExpired(client);
            }
        }
    }

    private void closeExpired(CosyVoiceWebSocketClient client) {
        client.close("expired or closed");
        totalConnections = Math.max(0, totalConnections - 1);
    }

    private boolean isExpired(CosyVoiceWebSocketClient client) {
        long idleNs = Math.max(0L, nanoTime.getAsLong() - client.lastUsedAtNs());
        return idleNs > TimeUnit.MILLISECONDS.toNanos(properties.getPool().getIdleTtlMs());
    }

    private long idleMs(CosyVoiceWebSocketClient client) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoTime.getAsLong() - client.lastUsedAtNs()));
    }
}
