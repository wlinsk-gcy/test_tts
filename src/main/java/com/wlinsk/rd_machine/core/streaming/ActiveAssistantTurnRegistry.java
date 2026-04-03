package com.wlinsk.rd_machine.core.streaming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ActiveAssistantTurnRegistry {

    private final Map<String, ActiveAssistantTurnHandle> activeTurns = new ConcurrentHashMap<>();

    public ActiveAssistantTurnHandle register(String sessionId) {
        ActiveAssistantTurnHandle handle = new ActiveAssistantTurnHandle();
        ActiveAssistantTurnHandle previousHandle = activeTurns.put(sessionId, handle);
        if (previousHandle != null) {
            previousHandle.cancel();
        }
        return handle;
    }

    public void complete(String sessionId, ActiveAssistantTurnHandle handle) {
        activeTurns.remove(sessionId, handle);
    }

    public void cancel(String sessionId) {
        ActiveAssistantTurnHandle handle = activeTurns.get(sessionId);
        if (handle != null) {
            handle.cancel();
        }
    }
}
