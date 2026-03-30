package com.wlinsk.rd_machine.logging;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.util.StringUtils;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LogPayloadSanitizer {

    private static final int MAX_TEXT_LENGTH = 256;

    private LogPayloadSanitizer() {
    }

    public static String toJson(Map<String, Object> payload) {
        return JSON.toJSONString(payload);
    }

    public static Object sanitizePayload(String payload) {
        if (payload == null) {
            return null;
        }
        if (!StringUtils.hasText(payload)) {
            return payload;
        }
        try {
            return sanitizeValue(JSON.parse(payload));
        } catch (Exception ignored) {
            return truncateText(payload);
        }
    }

    public static Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return truncateText(text);
        }
        if (value instanceof JSONObject jsonObject) {
            return new JSONObject(sanitizeMap(jsonObject));
        }
        if (value instanceof JSONArray jsonArray) {
            return new JSONArray(sanitizeList(jsonArray));
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            return sanitizeIterable(iterable);
        }
        if (value instanceof String[] stringArray) {
            List<Object> items = new ArrayList<>(stringArray.length);
            for (String item : stringArray) {
                items.add(sanitizeValue(item));
            }
            return items;
        }
        if (value instanceof byte[] bytes) {
            return "[binary payload, originalLength=" + bytes.length + "]";
        }
        return value;
    }

    public static Map<String, Object> headers(Enumeration<String> headerNames, java.util.function.Function<String, String> valueProvider) {
        Map<String, Object> headers = new LinkedHashMap<>();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, valueProvider.apply(headerName));
        }
        return headers;
    }

    public static Map<String, Object> requestHeaders(ContentCachingRequestWrapper request) {
        return headers(request.getHeaderNames(), request::getHeader);
    }

    public static Map<String, Object> responseHeaders(ContentCachingResponseWrapper response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String headerName : response.getHeaderNames()) {
            headers.put(headerName, response.getHeader(headerName));
        }
        return headers;
    }

    public static String requestBody(ContentCachingRequestWrapper request) {
        return contentAsString(request.getContentAsByteArray(), request.getCharacterEncoding());
    }

    public static String responseBody(ContentCachingResponseWrapper response) {
        return contentAsString(response.getContentAsByteArray(), response.getCharacterEncoding());
    }

    public static Object requestPayload(ContentCachingRequestWrapper request) {
        String body = requestBody(request);
        if (StringUtils.hasText(body)) {
            return sanitizePayload(body);
        }
        if (!request.getParameterMap().isEmpty()) {
            return sanitizeValue(request.getParameterMap());
        }
        return null;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            sanitized.put(key, sanitizeField(key, value));
        }
        return sanitized;
    }

    private static List<Object> sanitizeList(Iterable<?> source) {
        return sanitizeIterable(source);
    }

    private static List<Object> sanitizeIterable(Iterable<?> source) {
        List<Object> sanitized = new ArrayList<>();
        for (Object item : source) {
            sanitized.add(sanitizeValue(item));
        }
        return sanitized;
    }

    private static Object sanitizeField(String key, Object value) {
        if (key != null && key.toLowerCase(Locale.ROOT).contains("base64")) {
            int originalLength = value instanceof String text ? text.length() : -1;
            if (originalLength >= 0) {
                return "[omitted base64 payload, originalLength=" + originalLength + "]";
            }
            return "[omitted base64 payload]";
        }
        return sanitizeValue(value);
    }

    private static String contentAsString(byte[] content, String encoding) {
        if (content == null || content.length == 0) {
            return null;
        }
        Charset charset = StringUtils.hasText(encoding)
                ? Charset.forName(encoding)
                : StandardCharsets.UTF_8;
        return new String(content, charset);
    }

    private static String truncateText(String value) {
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_TEXT_LENGTH) + "[truncated, originalLength=" + value.length() + "]";
    }
}
