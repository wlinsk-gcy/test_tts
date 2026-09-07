package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.core.tts.TtsAudioListener;
import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;
import com.wlinsk.rd_machine.core.tts.TtsUtterance;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QwenAudioCallbackIsolationTest {

    @Test
    void sdkCallbackReturnsWhileDownstreamAudioListenerIsBlocked() throws Exception {
        AiTtsProperties properties = new AiTtsProperties();
        properties.setApiKey("test-key");
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(pool.borrow()).thenReturn(synthesizer);
        AtomicReference<ResultCallback<SpeechSynthesisResult>> callbackRef = new AtomicReference<>();
        doAnswer(invocation -> {
            callbackRef.set(invocation.getArgument(1));
            return null;
        }).when(synthesizer).updateParamAndCallback(any(), any());

        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch callbackReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            SpeechSynthesisResult result = new SpeechSynthesisResult();
            result.setAudioFrame(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            callbackRef.get().onEvent(result);
            callbackReturned.countDown();
            callbackRef.get().onComplete();
            return null;
        }).when(synthesizer).streamingComplete(anyLong());

        ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        try {
            QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, executor);
            TtsRealtimeSession session = client.openSession("longanhuan_v3.6", "zh");
            TtsUtterance utterance = session.openUtterance(new TtsAudioListener() {
                @Override
                public void onAudioChunk(int segmentSeq, byte[] audioBytes) {
                    listenerStarted.countDown();
                    try {
                        releaseListener.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onCompleted() {
                }

                @Override
                public void onError(Throwable throwable) {
                }
            });
            utterance.enqueue(new TextSegment(1, "测试。"));
            utterance.finish();

            assertTrue(listenerStarted.await(1, TimeUnit.SECONDS));
            try {
                assertTrue(callbackReturned.await(1, TimeUnit.SECONDS), "SDK callback must not wait for downstream WebSocket work");
            } finally {
                releaseListener.countDown();
            }
            utterance.awaitFinished(Duration.ofSeconds(2));
            session.close();
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
        }
    }
}
