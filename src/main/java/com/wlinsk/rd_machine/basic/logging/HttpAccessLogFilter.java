package com.wlinsk.rd_machine.basic.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HttpAccessLogFilter extends OncePerRequestFilter {

    private static final int REQUEST_CACHE_LIMIT = 1024 * 1024;
    private static final Set<String> STREAMING_ENDPOINTS = Set.of(
            "/api/tts/sessions/stream",
            "/api/cosyvoice/tts/stream"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (isStreamingRequest(request)) {
            doStreamingFilter(request, response, filterChain);
            return;
        }
        ContentCachingRequestWrapper wrappedRequest = request instanceof ContentCachingRequestWrapper contentCachingRequestWrapper
                ? contentCachingRequestWrapper
                : new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT);
        ContentCachingResponseWrapper wrappedResponse = response instanceof ContentCachingResponseWrapper contentCachingResponseWrapper
                ? contentCachingResponseWrapper
                : new ContentCachingResponseWrapper(response);
        long startedAtNs = System.nanoTime();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            try {
                logRequestAndResponse(wrappedRequest, wrappedResponse, startedAtNs);
            } catch (Exception exception) {
                log.warn("failed to write http access log", exception);
            } finally {
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    private void doStreamingFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = request instanceof ContentCachingRequestWrapper contentCachingRequestWrapper
                ? contentCachingRequestWrapper
                : new ContentCachingRequestWrapper(request, REQUEST_CACHE_LIMIT);
        long startedAtNs = System.nanoTime();
        try {
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            try {
                logStreamingRequestAndResponse(wrappedRequest, response, startedAtNs);
            } catch (Exception exception) {
                log.warn("failed to write http access log", exception);
            }
        }
    }

    private void logRequestAndResponse(
            ContentCachingRequestWrapper request,
            ContentCachingResponseWrapper response,
            long startedAtNs
    ) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("category", "http");
        logEntry.put("method", request.getMethod());
        logEntry.put("uri", request.getRequestURI());
        logEntry.put("query", request.getQueryString());
        logEntry.put("requestHeaders", LogPayloadSanitizer.requestHeaders(request));
        logEntry.put("requestBody", LogPayloadSanitizer.requestPayload(request));
        logEntry.put("responseStatus", response.getStatus());
        logEntry.put("responseHeaders", LogPayloadSanitizer.responseHeaders(response));
        logEntry.put("responseBody", LogPayloadSanitizer.sanitizePayload(LogPayloadSanitizer.responseBody(response)));
        logEntry.put("elapsedMs", Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L));
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    private void logStreamingRequestAndResponse(
            ContentCachingRequestWrapper request,
            HttpServletResponse response,
            long startedAtNs
    ) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("category", "http");
        logEntry.put("method", request.getMethod());
        logEntry.put("uri", request.getRequestURI());
        logEntry.put("query", request.getQueryString());
        logEntry.put("requestHeaders", LogPayloadSanitizer.requestHeaders(request));
        logEntry.put("requestBody", LogPayloadSanitizer.requestPayload(request));
        logEntry.put("responseStatus", response.getStatus());
        logEntry.put("responseHeaders", LogPayloadSanitizer.responseHeaders(response));
        logEntry.put("responseBody", "[stream omitted]");
        logEntry.put("elapsedMs", Math.max(0L, (System.nanoTime() - startedAtNs) / 1_000_000L));
        log.info("{}", LogPayloadSanitizer.toJson(logEntry));
    }

    private boolean isStreamingRequest(HttpServletRequest request) {
        return STREAMING_ENDPOINTS.contains(request.getRequestURI());
    }
}
