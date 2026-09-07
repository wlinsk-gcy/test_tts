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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class QwenAudioRealtimeTtsClient {

    private static final long QUEUE_OFFER_TIMEOUT_MS = 250L;
    private static final int TASK_SEGMENT_SEQ = 1;
    private static final TextSegment FINISH_SENTINEL = new TextSegment(-1, "");
    private static final CallbackSignal CALLBACK_FINISH_SENTINEL = new CallbackSignal(-1, null, null);

    private final AiTtsProperties properties;
    private final QwenAudioSpeechSynthesizerPool pool;
    private final ExecutorService executorService;

    public QwenAudioRealtimeTtsClient(
            AiTtsProperties properties,
            QwenAudioSpeechSynthesizerPool pool,
            @Qualifier("ttsStreamingExecutor") ExecutorService executorService
    ) {
        if (properties.getStreamQueueCapacity() <= 0) {
            throw new BasicException(SysCode.PARAMETER_ERROR.getCode(), "Qwen Audio TTS queue capacity must be positive");
        }
        this.properties = properties;
        this.pool = pool;
        this.executorService = executorService;
    }

    public TtsRealtimeSession openSession(String voice, String languageHint) {
        return new LogicalTtsSession(voice, languageHint);
    }

    private SpeechSynthesisParam buildParam(String voice, String languageHint) {
        return SpeechSynthesisParam.builder()
                .apiKey(resolveApiKey())
                .model(properties.getModel())
                .voice(voice)
                .format(QwenAudioAudioFormatResolver.resolve(properties.getResponseFormat(), properties.getSampleRate()))
                .languageHints(List.of(languageHint))
                .firstPackageTimeout(properties.getTaskTimeoutMs())
                .build();
    }

    private String resolveApiKey() {
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            return properties.getApiKey();
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }

    private final class LogicalTtsSession implements TtsRealtimeSession {

        private final String voice;
        private final String languageHint;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean utteranceActive = new AtomicBoolean();
        private final AtomicReference<QwenAudioUtterance> currentUtterance = new AtomicReference<>();

        private LogicalTtsSession(String voice, String languageHint) {
            this.voice = voice;
            this.languageHint = languageHint;
        }

        @Override
        public synchronized TtsUtterance openUtterance(TtsAudioListener audioListener) {
            if (closed.get()) {
                throw new BasicException(SysCode.TTS_STREAM_FAILED);
            }
            if (!utteranceActive.compareAndSet(false, true)) {
                throw new BasicException(SysCode.TTS_SESSION_BUSY);
            }
            QwenAudioUtterance utterance = new QwenAudioUtterance(
                    audioListener,
                    voice,
                    languageHint,
                    () -> utteranceActive.set(false)
            );
            currentUtterance.set(utterance);
            executorService.submit(() -> {
                try {
                    utterance.drain();
                } finally {
                    currentUtterance.compareAndSet(utterance, null);
                }
            });
            return utterance;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            QwenAudioUtterance utterance = currentUtterance.get();
            if (utterance != null) {
                utterance.cancel(new CancellationException("TTS session closed"));
            }
        }
    }

    private final class QwenAudioUtterance implements TtsUtterance {

        private final TtsAudioListener audioListener;
        private final String voice;
        private final String languageHint;
        private final Runnable releaseActiveGuard;
        private final BlockingQueue<TextSegment> textQueue;
        private final BlockingQueue<CallbackSignal> callbackQueue;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CompletableFuture<Void> providerCompletion = new CompletableFuture<>();
        private final CompletableFuture<Void> callbackDelivery = new CompletableFuture<>();
        private final AtomicReference<SpeechSynthesizer> activeSynthesizer = new AtomicReference<>();
        private final AtomicBoolean finishRequested = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean taskStarted = new AtomicBoolean();
        private final AtomicBoolean callbackClosed = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private QwenAudioUtterance(
                TtsAudioListener audioListener,
                String voice,
                String languageHint,
                Runnable releaseActiveGuard
        ) {
            this.audioListener = audioListener;
            this.voice = voice;
            this.languageHint = languageHint;
            this.releaseActiveGuard = releaseActiveGuard;
            this.textQueue = new ArrayBlockingQueue<>(properties.getStreamQueueCapacity());
            this.callbackQueue = new ArrayBlockingQueue<>(properties.getStreamQueueCapacity());
        }

        @Override
        public void enqueue(TextSegment textSegment) {
            if (finishRequested.get() || cancelRequested.get() || textSegment == null || textSegment.text().isBlank()) {
                return;
            }
            offerTextOrThrow(textSegment, "Interrupted while enqueueing Qwen Audio TTS segment");
        }

        @Override
        public void finish() {
            if (finishRequested.compareAndSet(false, true)) {
                offerTextOrThrow(FINISH_SENTINEL, "Interrupted while finishing Qwen Audio TTS utterance");
            }
        }

        @Override
        public void awaitFinished(Duration timeout) {
            try {
                completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted while awaiting Qwen Audio TTS");
            } catch (TimeoutException exception) {
                cancel(exception);
                throw new BasicException(SysCode.TTS_STREAM_FAILED.getCode(), "Timed out awaiting Qwen Audio TTS");
            } catch (ExecutionException exception) {
                throw new java.util.concurrent.CompletionException(exception.getCause());
            }
        }

        private void drain() {
            SpeechSynthesizer synthesizer = null;
            Throwable failure = null;
            boolean success = false;
            long startedAtNanos = System.nanoTime();
            try {
                startCallbackDelivery();
                throwIfCancelled();
                synthesizer = pool.borrow();
                activeSynthesizer.set(synthesizer);
                throwIfCancelled();
                synthesizer.updateParamAndCallback(buildParam(voice, languageHint), callback());
                audioListener.onSessionReady();
                while (true) {
                    TextSegment segment = textQueue.take();
                    if (segment.segmentSeq() < 0) {
                        break;
                    }
                    throwIfCancelled();
                    taskStarted.set(true);
                    synthesizer.streamingCall(segment.text());
                }
                throwIfCancelled();
                if (taskStarted.get()) {
                    synthesizer.streamingComplete(properties.getTaskTimeoutMs());
                    awaitProviderCompletion();
                }
                throwIfCancelled();
                success = true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = new CancellationException("Qwen Audio TTS worker interrupted");
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                signalCallbackDone();
                Throwable callbackFailure = awaitCallbackDeliveryFailure();
                if (callbackFailure != null && success) {
                    success = false;
                    failure = callbackFailure;
                }
                activeSynthesizer.compareAndSet(synthesizer, null);
                if (synthesizer != null) {
                    if (success) {
                        String requestId = synthesizer.getLastRequestId();
                        long firstPackageDelayMs = synthesizer.getFirstPackageDelay();
                        log.info(
                                "Qwen Audio TTS task completed: requestId={}, voice={}, firstPackageDelayMs={}, elapsedMs={}",
                                requestId,
                                voice,
                                firstPackageDelayMs,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
                        );
                        pool.release(synthesizer);
                    } else if (cancelRequested.get() && !taskStarted.get()) {
                        pool.release(synthesizer);
                    } else {
                        pool.invalidate(synthesizer, cancelRequested.get() ? "task cancelled" : "task failed");
                    }
                }
                releaseActiveGuard.run();
                if (success) {
                    completeSuccessfully();
                } else {
                    completeExceptionally(failure == null ? new IllegalStateException("Qwen Audio TTS failed") : failure);
                }
            }
        }

        private void startCallbackDelivery() {
            executorService.submit(() -> {
                try {
                    while (true) {
                        CallbackSignal signal = callbackQueue.take();
                        if (signal == CALLBACK_FINISH_SENTINEL) {
                            callbackDelivery.complete(null);
                            return;
                        }
                        if (cancelRequested.get()) {
                            continue;
                        }
                        if (signal.audioBytes() != null) {
                            audioListener.onAudioChunk(signal.segmentSeq(), signal.audioBytes());
                        } else if (signal.usageCharacters() != null) {
                            audioListener.onUsage(new TtsUsage(signal.usageCharacters()));
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    callbackDelivery.completeExceptionally(
                            new CancellationException("Qwen Audio TTS callback delivery interrupted")
                    );
                } catch (Throwable throwable) {
                    providerCompletion.completeExceptionally(throwable);
                    cancelUpstreamAfterCallbackFailure();
                    callbackDelivery.completeExceptionally(throwable);
                }
            });
        }

        private ResultCallback<SpeechSynthesisResult> callback() {
            return new ResultCallback<>() {
                @Override
                public void onEvent(SpeechSynthesisResult result) {
                    if (result == null || cancelRequested.get() || callbackClosed.get()) {
                        return;
                    }
                    try {
                        ByteBuffer audioFrame = result.getAudioFrame();
                        if (audioFrame != null && audioFrame.hasRemaining()) {
                            offerCallbackOrFail(new CallbackSignal(
                                    TASK_SEGMENT_SEQ,
                                    copyBytes(audioFrame),
                                    null
                            ));
                        }
                        SpeechSynthesisUsage usage = result.getUsage();
                        if (usage != null && usage.getCharacters() != null) {
                            long rawCharacters = usage.getCharacters().longValue();
                            log.info(
                                    "Qwen Audio TTS raw task usage: requestId={}, characters={}",
                                    requestId(),
                                    rawCharacters
                            );
                            offerCallbackOrFail(new CallbackSignal(0, null, rawCharacters));
                        }
                    } catch (Throwable throwable) {
                        providerCompletion.completeExceptionally(throwable);
                        signalCallbackDone();
                    }
                }

                @Override
                public void onComplete() {
                    signalCallbackDone();
                    providerCompletion.complete(null);
                }

                @Override
                public void onError(Exception exception) {
                    signalCallbackDone();
                    providerCompletion.completeExceptionally(exception);
                }
            };
        }

        private void awaitProviderCompletion() throws Exception {
            try {
                providerCompletion.get(properties.getTaskTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception checkedException) {
                    throw checkedException;
                }
                throw new IllegalStateException(cause);
            }
        }

        private Throwable awaitCallbackDeliveryFailure() {
            try {
                callbackDelivery.get(properties.getTaskTimeoutMs(), TimeUnit.MILLISECONDS);
                return null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new CancellationException("Interrupted while delivering Qwen Audio TTS callbacks");
            } catch (ExecutionException exception) {
                return exception.getCause();
            } catch (TimeoutException exception) {
                return new IllegalStateException("Timed out delivering Qwen Audio TTS callbacks", exception);
            }
        }

        private void offerCallbackOrFail(CallbackSignal signal) throws InterruptedException {
            if (callbackClosed.get()) {
                return;
            }
            if (!callbackQueue.offer(signal, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new BasicException(SysCode.TTS_SEGMENT_QUEUE_FULL);
            }
        }

        private void signalCallbackDone() {
            if (!callbackClosed.compareAndSet(false, true)) {
                return;
            }
            try {
                executorService.submit(() -> {
                    try {
                        callbackQueue.put(CALLBACK_FINISH_SENTINEL);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        callbackDelivery.completeExceptionally(
                                new CancellationException("Interrupted while closing Qwen Audio TTS callback delivery")
                        );
                    }
                });
            } catch (RejectedExecutionException exception) {
                callbackDelivery.completeExceptionally(exception);
            }
        }

        private void cancel(Throwable cause) {
            if (!cancelRequested.compareAndSet(false, true)) {
                return;
            }
            finishRequested.set(true);
            SpeechSynthesizer synthesizer = activeSynthesizer.get();
            if (synthesizer != null && taskStarted.get()) {
                try {
                    synthesizer.streamingCancel();
                } catch (Exception exception) {
                    log.debug("Failed to cancel Qwen Audio TTS task", exception);
                }
            }
            providerCompletion.completeExceptionally(cause);
            signalCallbackDone();
            textQueue.offer(FINISH_SENTINEL);
        }

        private void cancelUpstreamAfterCallbackFailure() {
            SpeechSynthesizer synthesizer = activeSynthesizer.get();
            if (synthesizer == null || !taskStarted.get()) {
                return;
            }
            try {
                synthesizer.streamingCancel();
            } catch (Exception exception) {
                log.debug("Failed to cancel Qwen Audio TTS after callback delivery failure", exception);
            }
        }

        private String requestId() {
            SpeechSynthesizer synthesizer = activeSynthesizer.get();
            return synthesizer == null ? null : synthesizer.getLastRequestId();
        }

        private void completeSuccessfully() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                audioListener.onCompleted();
                completion.complete(null);
            } catch (Throwable throwable) {
                completion.completeExceptionally(throwable);
            }
        }

        private void completeExceptionally(Throwable throwable) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                audioListener.onError(throwable);
            } finally {
                completion.completeExceptionally(throwable);
            }
        }

        private void throwIfCancelled() {
            if (cancelRequested.get()) {
                throw new CancellationException("Qwen Audio TTS task cancelled");
            }
        }

        private void offerTextOrThrow(TextSegment textSegment, String interruptionMessage) {
            try {
                if (!textQueue.offer(textSegment, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new BasicException(SysCode.TTS_SEGMENT_QUEUE_FULL);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException(interruptionMessage);
            }
        }

        private byte[] copyBytes(ByteBuffer data) {
            ByteBuffer copy = data.slice();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
    }

    private record CallbackSignal(int segmentSeq, byte[] audioBytes, Long usageCharacters) {
    }
}
