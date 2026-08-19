package com.aifanyi.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Per-route concurrency admission control and runtime metrics for every API endpoint. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiConcurrencyMonitor extends OncePerRequestFilter {
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final int maxConcurrency;
    private final int acquireTimeoutMs;

    public ApiConcurrencyMonitor(ObjectMapper objectMapper,
                                 @Value("${aifanyi.api.max-concurrency-per-route:50}") int maxConcurrency,
                                 @Value("${aifanyi.api.acquire-timeout-ms:30000}") int acquireTimeoutMs) {
        this.objectMapper = objectMapper;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
    }

    public record Snapshot(String method, String path, int active, int waiting, int limit,
                           int peak, long total, long rejected) {}

    private final class Counter {
        private final Semaphore permits = new Semaphore(maxConcurrency, true);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger waiting = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final LongAdder total = new LongAdder();
        private final LongAdder rejected = new LongAdder();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod().toUpperCase();
        String path = normalizePath(request.getRequestURI());
        Counter counter = counters.computeIfAbsent(method + " " + path, ignored -> new Counter());
        counter.total.increment();

        boolean acquired = false;
        counter.waiting.incrementAndGet();
        try {
            acquired = counter.permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            counter.waiting.decrementAndGet();
        }

        if (!acquired) {
            counter.rejected.increment();
            response.setStatus(429);
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "2");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", 429);
            body.put("msg", "接口并发已达到 " + maxConcurrency + "，请稍后重试");
            body.put("data", null);
            objectMapper.writeValue(response.getWriter(), body);
            return;
        }

        int active = counter.active.incrementAndGet();
        counter.peak.accumulateAndGet(active, Math::max);
        try {
            filterChain.doFilter(request, response);
        } finally {
            counter.active.decrementAndGet();
            counter.permits.release();
        }
    }

    public List<Snapshot> snapshots() {
        return counters.entrySet().stream().map(entry -> {
            int split = entry.getKey().indexOf(' ');
            Counter counter = entry.getValue();
            return new Snapshot(entry.getKey().substring(0, split), entry.getKey().substring(split + 1),
                    counter.active.get(), counter.waiting.get(), maxConcurrency, counter.peak.get(),
                    counter.total.sum(), counter.rejected.sum());
        }).sorted(Comparator.comparing(Snapshot::path).thenComparing(Snapshot::method)).toList();
    }

    private static String normalizePath(String path) {
        return path.replaceAll("/(?i:[0-9a-f]{8}-[0-9a-f-]{27,})", "/{id}")
                .replaceAll("/\\d+(?=/|$)", "/{id}");
    }
}
