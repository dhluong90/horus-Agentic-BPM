package io.github.dhluong90.bpm.examples.models;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for both model APIs, on the JDK HTTP server bound to the loopback address: it answers
 * {@code POST /v1/chat/completions} and {@code POST /v1/messages} with recorded responses from
 * {@code src/test/resources/recorded/}, and keeps every request it received. Tests queue the answers they need; no
 * test reaches a real provider or needs a key.
 */
final class StubModelServer implements AutoCloseable {

    static final String OPENAI = "/v1/chat/completions";
    static final String ANTHROPIC = "/v1/messages";

    /**
     * One request the stub received.
     *
     * @param path the path
     * @param headers the headers, names in lower case
     * @param body the JSON body
     * @param raw the body as sent
     */
    record Request(String path, Map<String, String> headers, JsonNode body, String raw) {
    }

    /**
     * One answer.
     *
     * @param status the HTTP status
     * @param headers extra headers
     * @param body the body
     */
    record Answer(int status, Map<String, String> headers, String body) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Answer>> queued = new LinkedHashMap<>();
    private final Map<String, Answer> defaults = new LinkedHashMap<>();
    private volatile CountDownLatch hold;

    StubModelServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-model-api");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        defaults.put(OPENAI, recorded("openai/accepted.json", 200));
        defaults.put(ANTHROPIC, recorded("anthropic/accepted.json", 200));
        server.start();
    }

    /** @return the base address of the OpenAI-compatible API, ending in {@code /v1} */
    URI openAiBase() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1");
    }

    /** @return the base address of the Anthropic Messages API */
    URI anthropicBase() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }

    /**
     * @param path the recorded file under {@code recorded/}
     * @param status the status to answer with
     * @return the answer
     */
    static Answer recorded(String path, int status) {
        return recorded(path, status, Map.of());
    }

    static Answer recorded(String path, int status, Map<String, String> headers) {
        try (InputStream in = StubModelServer.class.getResourceAsStream("/recorded/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("No recorded response " + path);
            }
            return new Answer(status, headers, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Queues the next answers of an API, after which it answers its default again. */
    synchronized StubModelServer then(String path, Answer... answers) {
        Deque<Answer> queue = queued.computeIfAbsent(path, p -> new ArrayDeque<>());
        queue.addAll(List.of(answers));
        return this;
    }

    /** Holds every answer until the latch opens. */
    void hold(CountDownLatch latch) {
        hold = latch;
    }

    /** @return every request received, in order */
    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Forgets the requests and the queued answers. */
    synchronized void reset() {
        requests.clear();
        queued.clear();
        hold = null;
    }

    private synchronized Answer next(String path) {
        Deque<Answer> queue = queued.get(path);
        if (queue != null && !queue.isEmpty()) {
            return queue.removeFirst();
        }
        return defaults.getOrDefault(path, new Answer(404, Map.of(), "{\"error\":\"not found\"}"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(path, Map.copyOf(headers), JSON.readTree(raw), raw));
            CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer answer = next(path);
            answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // The client went away (a timeout test); nothing to answer.
        }
    }

    @Override
    public void close() {
        CountDownLatch latch = hold;
        if (latch != null) {
            latch.countDown();
        }
        server.stop(0);
    }
}
