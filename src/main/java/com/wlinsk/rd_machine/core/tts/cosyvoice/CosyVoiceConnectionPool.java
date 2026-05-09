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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
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
    private final Map<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>> idleConnections = new HashMap<>();
    private final Map<CosyVoiceWebSocketClient, CosyVoiceConnectionKey> connectionKeys = new IdentityHashMap<>();
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

    public CosyVoiceWebSocketClient acquire(CosyVoiceConnectionKey key) {
        long waitDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getPool().getAcquireTimeoutMs());
        while (true) {
            synchronized (this) {
                cleanupExpiredIdleConnections();
                CosyVoiceWebSocketClient reusable = pollReusable(key);
                if (reusable != null) {
                    log.info(
                            "cosyvoice.connection.reused connectionId={} connectionKey={} idleMs={}",
                            reusable.connectionId(),
                            key,
                            idleMs(reusable)
                    );
                    return reusable;
                }
                if (totalConnections < properties.getPool().getMaxSize()) {
                    totalConnections += 1;
                    break;
                }
                if (evictIncompatibleIdleConnection(key)) {
                    continue;
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
            synchronized (this) {
                connectionKeys.put(client, key);
            }
            return client;
        } catch (Exception exception) {
            client.close("connect failed");
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
            CosyVoiceConnectionKey key = connectionKeys.get(client);
            if (key != null && client.isOpen() && !isExpired(client)) {
                idleConnections.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(client);
                notifyAll();
                log.info("cosyvoice.connection.released connectionId={} connectionKey={}", client.connectionId(), key);
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
            CosyVoiceConnectionKey key = connectionKeys.remove(client);
            boolean tracked = key != null;
            if (key != null) {
                Deque<CosyVoiceWebSocketClient> keyedIdleConnections = idleConnections.get(key);
                if (keyedIdleConnections != null) {
                    keyedIdleConnections.remove(client);
                    if (keyedIdleConnections.isEmpty()) {
                        idleConnections.remove(key);
                    }
                }
            } else {
                tracked = removeIdleConnection(client);
            }
            if (tracked) {
                totalConnections = Math.max(0, totalConnections - 1);
            }
            notifyAll();
        }
    }

    synchronized int totalConnections() {
        return totalConnections;
    }

    private CosyVoiceWebSocketClient pollReusable(CosyVoiceConnectionKey key) {
        Deque<CosyVoiceWebSocketClient> keyedIdleConnections = idleConnections.get(key);
        while (keyedIdleConnections != null && !keyedIdleConnections.isEmpty()) {
            CosyVoiceWebSocketClient client = keyedIdleConnections.removeFirst();
            if (client.isOpen() && !isExpired(client)) {
                if (keyedIdleConnections.isEmpty()) {
                    idleConnections.remove(key);
                }
                return client;
            }
            closeExpired(client);
        }
        idleConnections.remove(key);
        return null;
    }

    private boolean evictIncompatibleIdleConnection(CosyVoiceConnectionKey requestedKey) {
        CosyVoiceConnectionKey evictedKey = null;
        CosyVoiceWebSocketClient evictedClient = null;
        for (Map.Entry<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>> entry : idleConnections.entrySet()) {
            if (entry.getKey().equals(requestedKey)) {
                continue;
            }
            for (CosyVoiceWebSocketClient client : entry.getValue()) {
                if (evictedClient == null || client.lastUsedAtNs() < evictedClient.lastUsedAtNs()) {
                    evictedKey = entry.getKey();
                    evictedClient = client;
                }
            }
        }
        if (evictedClient == null) {
            return false;
        }

        Deque<CosyVoiceWebSocketClient> keyedIdleConnections = idleConnections.get(evictedKey);
        if (keyedIdleConnections != null) {
            keyedIdleConnections.remove(evictedClient);
            if (keyedIdleConnections.isEmpty()) {
                idleConnections.remove(evictedKey);
            }
        }
        connectionKeys.remove(evictedClient);
        totalConnections = Math.max(0, totalConnections - 1);
        notifyAll();
        log.info(
                "cosyvoice.connection.evicted connectionId={} connectionKey={} requestedConnectionKey={} idleMs={}",
                evictedClient.connectionId(),
                evictedKey,
                requestedKey,
                idleMs(evictedClient)
        );
        evictedClient.close("evicted for incompatible key");
        return true;
    }

    private void cleanupExpiredIdleConnections() {
        Iterator<Map.Entry<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>>> entryIterator =
                idleConnections.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>> entry = entryIterator.next();
            Iterator<CosyVoiceWebSocketClient> connectionIterator = entry.getValue().iterator();
            while (connectionIterator.hasNext()) {
                CosyVoiceWebSocketClient client = connectionIterator.next();
                if (!client.isOpen() || isExpired(client)) {
                    connectionIterator.remove();
                    closeExpired(client);
                }
            }
            if (entry.getValue().isEmpty()) {
                entryIterator.remove();
            }
        }
    }

    private void closeExpired(CosyVoiceWebSocketClient client) {
        client.close("expired or closed");
        if (connectionKeys.remove(client) != null) {
            totalConnections = Math.max(0, totalConnections - 1);
        }
    }

    private boolean removeIdleConnection(CosyVoiceWebSocketClient client) {
        Iterator<Map.Entry<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>>> entryIterator =
                idleConnections.entrySet().iterator();
        while (entryIterator.hasNext()) {
            Map.Entry<CosyVoiceConnectionKey, Deque<CosyVoiceWebSocketClient>> entry = entryIterator.next();
            if (entry.getValue().remove(client)) {
                if (entry.getValue().isEmpty()) {
                    entryIterator.remove();
                }
                return true;
            }
            if (entry.getValue().isEmpty()) {
                entryIterator.remove();
            }
        }
        return false;
    }

    private boolean isExpired(CosyVoiceWebSocketClient client) {
        long idleNs = Math.max(0L, nanoTime.getAsLong() - client.lastUsedAtNs());
        return idleNs > TimeUnit.MILLISECONDS.toNanos(properties.getPool().getIdleTtlMs());
    }

    private long idleMs(CosyVoiceWebSocketClient client) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoTime.getAsLong() - client.lastUsedAtNs()));
    }
}
