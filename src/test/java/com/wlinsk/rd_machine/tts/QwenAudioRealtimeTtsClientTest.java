package com.wlinsk.rd_machine.core.tts.qwenaudio;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisUsage;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;
import com.wlinsk.rd_machine.basic.config.AiTtsProperties;
import com.wlinsk.rd_machine.basic.enums.SysCode;
import com.wlinsk.rd_machine.basic.exception.BasicException;
import com.wlinsk.rd_machine.basic.model.bo.TextSegment;
import com.wlinsk.rd_machine.basic.model.bo.TtsUsage;
import com.wlinsk.rd_machine.core.tts.TtsAudioListener;
import com.wlinsk.rd_machine.core.tts.TtsRealtimeSession;
import com.wlinsk.rd_machine.core.tts.TtsUtterance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QwenAudioRealtimeTtsClientTest {

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("qwen-audio-test-", 0).factory()
    );

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void reusesPooledSynthesizerButKeepsUsagePerUtterance() {
        AiTtsProperties properties = properties();
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(pool.borrow()).thenReturn(synthesizer);

        AtomicReference<ResultCallback<SpeechSynthesisResult>> callbackRef = new AtomicReference<>();
        Queue<List<Integer>> rawTaskUsage = new ArrayDeque<>(List.of(
                List.of(13, 26),
                List.of(13, 26)
        ));
        doAnswer(invocation -> {
            callbackRef.set(invocation.getArgument(1));
            return null;
        }).when(synthesizer).updateParamAndCallback(any(), any());
        doAnswer(invocation -> {
            ResultCallback<SpeechSynthesisResult> callback = callbackRef.get();
            for (int characters : rawTaskUsage.remove()) {
                callback.onEvent(result(new byte[]{1, 2, 3}, characters));
            }
            callback.onComplete();
            return null;
        }).when(synthesizer).streamingComplete(anyLong());

        QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, executor);
        TtsRealtimeSession session = client.openSession("longanhuan_v3.6", "zh");
        RecordingListener first = runUtterance(session, "第一段，", "第二段。", List.of(13L, 26L));
        RecordingListener second = runUtterance(session, "第三段，", "第四段。", List.of(13L, 26L));

        assertArrayEquals(new byte[]{1, 2, 3}, first.audioChunks.getFirst());
        assertArrayEquals(new byte[]{1, 2, 3}, second.audioChunks.getFirst());
        assertEquals(List.of(1, 1), first.audioSegmentSequences);
        assertEquals(List.of(1, 1), second.audioSegmentSequences);
        assertEquals(List.of(13L, 26L), first.usageCharacters);
        assertEquals(List.of(13L, 26L), second.usageCharacters);
        verify(pool, times(2)).release(synthesizer);

        InOrder order = inOrder(synthesizer);
        order.verify(synthesizer).streamingCall("第一段，");
        order.verify(synthesizer).streamingCall("第二段。");
        order.verify(synthesizer).streamingComplete(properties.getTaskTimeoutMs());
        order.verify(synthesizer).streamingCall("第三段，");
        order.verify(synthesizer).streamingCall("第四段。");
        order.verify(synthesizer).streamingComplete(properties.getTaskTimeoutMs());

        ArgumentCaptor<SpeechSynthesisParam> paramCaptor = ArgumentCaptor.forClass(SpeechSynthesisParam.class);
        verify(synthesizer, times(2)).updateParamAndCallback(paramCaptor.capture(), any());
        SpeechSynthesisParam param = paramCaptor.getAllValues().getFirst();
        assertEquals("qwen-audio-3.0-tts-flash", param.getModel());
        assertEquals("longanhuan_v3.6", param.getVoice());
        assertEquals(List.of("zh"), param.getLanguageHints());
        session.close();
    }

    @Test
    void invalidatesConnectionWhenTaskFails() {
        AiTtsProperties properties = properties();
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(pool.borrow()).thenReturn(synthesizer);
        doThrow(new IllegalStateException("upstream failed")).when(synthesizer).streamingCall("boom");
        QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, executor);
        TtsRealtimeSession session = client.openSession("loongmary", "en");
        RecordingListener listener = new RecordingListener();
        TtsUtterance utterance = session.openUtterance(listener);

        utterance.enqueue(new TextSegment(1, "boom"));
        utterance.finish();

        assertThrows(CompletionException.class, () -> utterance.awaitFinished(Duration.ofSeconds(2)));
        assertEquals(1, listener.errors.size());
        verify(pool).invalidate(synthesizer, "task failed");
        session.close();
    }

    @Test
    void cancelsAndInvalidatesAStartedTaskWhenSessionCloses() throws Exception {
        AiTtsProperties properties = properties();
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(pool.borrow()).thenReturn(synthesizer);
        CountDownLatch taskStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            taskStarted.countDown();
            return null;
        }).when(synthesizer).streamingCall("正在合成。");
        QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, executor);
        TtsRealtimeSession session = client.openSession("longanhuan_v3.6", "zh");
        RecordingListener listener = new RecordingListener();
        TtsUtterance utterance = session.openUtterance(listener);

        utterance.enqueue(new TextSegment(1, "正在合成。"));
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS));
        session.close();

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> utterance.awaitFinished(Duration.ofSeconds(2)));
        verify(synthesizer).streamingCancel();
        verify(pool).invalidate(synthesizer, "task cancelled");
        verify(pool, never()).release(synthesizer);
        assertEquals(1, listener.errors.size());
    }

    @Test
    void rejectsConcurrentUtterancesInOneLogicalSession() {
        AiTtsProperties properties = properties();
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        SpeechSynthesizer synthesizer = mock(SpeechSynthesizer.class);
        when(pool.borrow()).thenReturn(synthesizer);
        QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, executor);
        TtsRealtimeSession session = client.openSession("loongmary", "en");

        session.openUtterance(new RecordingListener());
        BasicException exception = assertThrows(
                BasicException.class,
                () -> session.openUtterance(new RecordingListener())
        );

        assertEquals(SysCode.TTS_SESSION_BUSY.getCode(), exception.getStatus());
        session.close();
    }

    @Test
    void closeCannotOvertakeAnUtteranceThatIsBeingOpened() throws Exception {
        AiTtsProperties properties = properties();
        QwenAudioSpeechSynthesizerPool pool = mock(QwenAudioSpeechSynthesizerPool.class);
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        ExecutorService blockingExecutor = mock(ExecutorService.class);
        CountDownLatch firstSubmitEntered = new CountDownLatch(1);
        CountDownLatch allowFirstSubmit = new CountDownLatch(1);
        AtomicInteger submitCount = new AtomicInteger();
        AtomicReference<Runnable> firstTask = new AtomicReference<>();
        when(blockingExecutor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            if (submitCount.incrementAndGet() == 1) {
                firstTask.set(task);
                firstSubmitEntered.countDown();
                assertTrue(allowFirstSubmit.await(2, TimeUnit.SECONDS));
                return CompletableFuture.completedFuture(null);
            }
            return delegate.submit(task);
        });

        try {
            QwenAudioRealtimeTtsClient client = new QwenAudioRealtimeTtsClient(properties, pool, blockingExecutor);
            TtsRealtimeSession session = client.openSession("longanhuan_v3.6", "zh");
            RecordingListener listener = new RecordingListener();
            AtomicReference<TtsUtterance> opened = new AtomicReference<>();
            Thread openThread = Thread.startVirtualThread(() -> opened.set(session.openUtterance(listener)));
            assertTrue(firstSubmitEntered.await(1, TimeUnit.SECONDS));

            CountDownLatch closeReturned = new CountDownLatch(1);
            Thread closeThread = Thread.startVirtualThread(() -> {
                session.close();
                closeReturned.countDown();
            });
            assertFalse(closeReturned.await(150, TimeUnit.MILLISECONDS),
                    "close must wait until openUtterance installs its cancellation target");

            allowFirstSubmit.countDown();
            openThread.join(1_000L);
            closeThread.join(1_000L);
            assertFalse(openThread.isAlive());
            assertFalse(closeThread.isAlive());
            assertTrue(session.isClosed());

            firstTask.get().run();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> opened.get().awaitFinished(Duration.ofSeconds(2)));
            assertEquals(1, listener.errors.size());
        } finally {
            allowFirstSubmit.countDown();
            delegate.shutdownNow();
        }
    }

    private RecordingListener runUtterance(
            TtsRealtimeSession session,
            String firstText,
            String secondText,
            List<Long> expectedUsage
    ) {
        RecordingListener listener = new RecordingListener();
        TtsUtterance utterance = session.openUtterance(listener);
        utterance.enqueue(new TextSegment(1, firstText));
        utterance.enqueue(new TextSegment(2, secondText));
        utterance.finish();
        utterance.awaitFinished(Duration.ofSeconds(2));
        assertEquals(expectedUsage, listener.usageCharacters);
        assertEquals(1, listener.completed);
        return listener;
    }

    private AiTtsProperties properties() {
        AiTtsProperties properties = new AiTtsProperties();
        properties.setApiKey("test-key");
        properties.setStreamQueueCapacity(8);
        return properties;
    }

    private SpeechSynthesisResult result(byte[] audio, int characters) {
        SpeechSynthesisResult result = new SpeechSynthesisResult();
        result.setAudioFrame(ByteBuffer.wrap(audio));
        result.setUsage(SpeechSynthesisUsage.builder().characters(characters).build());
        return result;
    }

    private static final class RecordingListener implements TtsAudioListener {

        private final List<byte[]> audioChunks = new ArrayList<>();
        private final List<Integer> audioSegmentSequences = new ArrayList<>();
        private final List<Long> usageCharacters = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();
        private int completed;

        @Override
        public synchronized void onAudioChunk(int segmentSeq, byte[] audioBytes) {
            audioSegmentSequences.add(segmentSeq);
            audioChunks.add(audioBytes);
        }

        @Override
        public synchronized void onUsage(TtsUsage usage) {
            usageCharacters.add(usage.characters());
        }

        @Override
        public synchronized void onCompleted() {
            completed += 1;
        }

        @Override
        public synchronized void onError(Throwable throwable) {
            errors.add(throwable);
        }
    }
}
