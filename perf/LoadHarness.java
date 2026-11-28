import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load harness for the example host (examples/rule-and-process). No dependency beyond the JDK (Java 21 or later):
 *
 *   java perf/LoadHarness.java --base http://127.0.0.1:8080 --password-file secrets/demo_password \
 *        --mode baseline --out perf/results/run.json
 *
 * It signs in once per demo user (form login, session cookie, CSRF header), as the cockpit does, so the measurement is
 * not the demo user store's password hashing. Each scenario runs a closed loop: N threads, each sends the next request
 * when the last answered. Latency is measured per operation from the client, in milliseconds, after a warm-up that is
 * discarded. A non-2xx answer is an error and is counted, never hidden. Compare a run with a baseline using
 * perf/check_regression.py.
 */
public final class LoadHarness {

    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9A-Z]{26})\"");
    private static final Pattern FIRST_TASK = Pattern.compile("\"tasks\":\\[\\{\"id\":\"([0-9A-Z]{26})\".*?\"lockVersion\":(\\d+)");
    private static final Pattern INSTANCE_ID = Pattern.compile("\"instanceId\":\"([0-9A-Z]{26})\"");
    private static final Pattern LOCK = Pattern.compile("\"lockVersion\":(\\d+)");
    private static final String RULES = "/api/bpm/rules/v1";
    private static final String PROCESSES = "/api/bpm/processes/v1";

    private final String base;
    private final HttpClient http;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Op> ops = new LinkedHashMap<>();
    private final String run = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean recording;
    private String password;
    private int measuredSeconds;

    private LoadHarness(String base) {
        this.base = base;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** One signed-in user: session cookie and CSRF token. */
    private record Session(String cookie, String xsrf) { }

    /** Latencies and error count of one named operation. */
    private static final class Op {
        final String name;
        final List<Long> nanos = new ArrayList<>();
        long errors;
        String firstError;

        Op(String name) {
            this.name = name;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        String base = a.getOrDefault("base", "http://127.0.0.1:8080");
        String mode = a.getOrDefault("mode", "baseline");
        boolean smoke = mode.equals("smoke");
        int threads = Integer.parseInt(a.getOrDefault("threads", smoke ? "4" : "16"));
        int duration = Integer.parseInt(a.getOrDefault("duration", smoke ? "5" : "30"));
        int warmup = Integer.parseInt(a.getOrDefault("warmup", smoke ? "2" : "8"));
        Path out = Path.of(a.getOrDefault("out", "perf/results/run.json"));
        String pw = a.containsKey("password-file")
                ? Files.readString(Path.of(a.get("password-file"))).strip() : System.getenv("HORUS_DEMO_PASSWORD");
        if (pw == null || pw.isBlank()) {
            throw new IllegalArgumentException("Give --password-file or set HORUS_DEMO_PASSWORD");
        }
        LoadHarness h = new LoadHarness(base);
        h.measuredSeconds = duration;
        h.password = pw;
        h.runAll(mode, threads, duration, warmup, out, a.getOrDefault("machine", ""));
    }

    private void runAll(String mode, int threads, int duration, int warmup, Path out, String machine) throws Exception {
        for (String u : List.of("tuan", "an", "hung", "lan", "minh", "hoa")) {
            sessions.put(u, login(u));
        }
        Map<String, Object> scenarios = new LinkedHashMap<>();
        scenarios.put("rule-evaluate", scenario(threads, duration, warmup, this::ruleEvaluate));
        scenarios.put("process-start", scenario(threads, duration, warmup, this::processStart));
        scenarios.put("task-flow", scenario(threads, duration, warmup, this::taskFlow));
        scenarios.put("message-flow", scenario(threads, duration, warmup, this::messageFlow));
        scenarios.put("cockpit-lists", scenario(Math.max(1, threads / 2), duration, warmup, this::listReads));

        StringBuilder j = new StringBuilder();
        j.append("{\n  \"schemaVersion\": 1,\n  \"mode\": ").append(q(mode))
                .append(",\n  \"startedAt\": ").append(q(Instant.now().toString()))
                .append(",\n  \"base\": ").append(q(base))
                .append(",\n  \"threads\": ").append(threads)
                .append(",\n  \"durationSeconds\": ").append(duration)
                .append(",\n  \"warmupSeconds\": ").append(warmup)
                .append(",\n  \"client\": ").append(q(System.getProperty("os.name") + " " + System.getProperty("os.arch")
                        + ", Java " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors() + " CPUs"))
                .append(",\n  \"machine\": ").append(q(machine))
                .append(",\n  \"scenarios\": {");
        boolean first = true;
        for (Map.Entry<String, Object> e : scenarios.entrySet()) {
            j.append(first ? "\n" : ",\n").append("    ").append(q(e.getKey())).append(": ").append(e.getValue());
            first = false;
        }
        j.append("\n  },\n  \"operations\": {");
        first = true;
        for (Op op : ops.values()) {
            j.append(first ? "\n" : ",\n").append("    ").append(q(op.name)).append(": ").append(summary(op));
            first = false;
        }
        j.append("\n  }\n}\n");
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, j.toString());
        System.out.print(table());
        System.out.println("written " + out);
    }

    // ---- scenarios -------------------------------------------------------------------------------------------

    private void ruleEvaluate() throws Exception {
        long n = seq.incrementAndGet();
        String tier = n % 3 == 0 ? "GOLD" : "STANDARD";
        call("rule.evaluate", "tuan", "POST", RULES + "/decisions/card-fee:evaluate",
                "{\"inputs\":{\"tier\":\"" + tier + "\",\"txCount\":" + (n % 9) + "}}", null, null);
    }

    /** A card application for a gold customer: the engine runs every step to the end in one transaction. */
    private void processStart() throws Exception {
        call("process.start", "tuan", "POST", "/api/cards/applications",
                "{\"applicationId\":\"" + id("PS") + "\",\"customerId\":\"C-001\"}", null, null);
    }

    /** Start to a user task, read the instance, claim and complete the task as another user (four eyes). */
    private void taskFlow() throws Exception {
        String r = call("process.start.to-user-task", "tuan", "POST", "/api/cards/applications",
                "{\"applicationId\":\"" + id("TF") + "\",\"customerId\":\"C-002\"}", null, null);
        Matcher m = INSTANCE_ID.matcher(r == null ? "" : r);
        if (!m.find()) {
            return;
        }
        String inst = call("instance.read", "an", "GET", PROCESSES + "/instances/" + m.group(1), null, null, null);
        Matcher t = FIRST_TASK.matcher(inst == null ? "" : inst);
        if (!t.find()) {
            error("instance.read", "no task in " + inst);
            return;
        }
        String task = t.group(1);
        String claimed = call("task.claim", "an", "POST", PROCESSES + "/tasks/" + task + ":claim", null,
                "\"" + t.group(2) + "\"", id("CL"));
        Matcher l = LOCK.matcher(claimed == null ? "" : claimed);
        if (!l.find()) {
            return;
        }
        call("task.complete", "an", "POST", PROCESSES + "/tasks/" + task + ":complete",
                "{\"result\":{\"approved\":true}}", "\"" + l.group(1) + "\"", id("CO"));
    }

    /** Start an order that waits for a payment, then deliver the payment message. */
    private void messageFlow() throws Exception {
        String order = id("ORD");
        call("process.start.to-wait-state", "tuan", "POST", PROCESSES + "/instances",
                "{\"definitionKey\":\"order-payment\",\"businessKey\":\"" + order + "\",\"variables\":{\"orderId\":\""
                        + order + "\",\"amount\":\"100\"}}", null, id("ST"));
        call("message.correlate", "hung", "POST", PROCESSES + "/messages:correlate",
                "{\"messageName\":\"payment-received\",\"correlationKey\":\"" + order
                        + "\",\"payload\":{\"reference\":\"TT-" + order + "\"}}", null, id("MC"));
    }

    /** The lists the cockpit shows, one page of 50 each. */
    private void listReads() throws Exception {
        String from = URLEncoder.encode(Instant.now().minus(Duration.ofDays(1)).toString(), StandardCharsets.UTF_8);
        String to = URLEncoder.encode(Instant.now().plus(Duration.ofMinutes(5)).toString(), StandardCharsets.UTF_8);
        call("list.instances", "tuan", "GET", PROCESSES + "/instances?limit=50", null, null, null);
        call("list.instances-completed", "tuan", "GET", PROCESSES + "/instances?status=COMPLETED&limit=50", null, null, null);
        call("list.tasks", "an", "GET", PROCESSES + "/tasks?limit=50", null, null, null);
        call("list.definitions", "lan", "GET", PROCESSES + "/definitions?limit=50", null, null, null);
        call("list.incidents", "hoa", "GET", PROCESSES + "/incidents?limit=50", null, null, null);
        call("list.rule-sets", "lan", "GET", RULES + "/rule-sets?limit=50", null, null, null);
        call("list.decisions", "minh", "GET", RULES + "/decisions?ruleSet=card-fee&from=" + from + "&to=" + to + "&limit=50",
                null, null, null);
    }

    // ---- driver ----------------------------------------------------------------------------------------------

    private interface Step {
        void run() throws Exception;
    }

    private String scenario(int threads, int duration, int warmup, Step step) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        long measureFrom = start + warmup * 1_000_000_000L;
        long end = measureFrom + duration * 1_000_000_000L;
        recording = false;
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                while (System.nanoTime() < end) {
                    if (!recording && System.nanoTime() >= measureFrom) {
                        recording = true;
                    }
                    try {
                        step.run();
                    } catch (Exception e) {
                        error("harness", e.toString());
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        recording = false;
        return "{ \"threads\": " + threads + ", \"measuredSeconds\": " + duration + " }";
    }

    private String call(String op, String user, String method, String path, String body, String ifMatch, String idem)
            throws IOException, InterruptedException {
        Session s = sessions.get(user);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Cookie", s.cookie).header("X-XSRF-TOKEN", s.xsrf).header("Accept", "application/json");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        if (idem != null) {
            b.header("Idempotency-Key", idem);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        long t0 = System.nanoTime();
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            record(op, System.nanoTime() - t0, false, e.toString());
            return null;
        }
        long dt = System.nanoTime() - t0;
        boolean ok = r.statusCode() / 100 == 2;
        record(op, dt, ok, ok ? null : r.statusCode() + " " + trim(r.body()));
        return ok ? r.body() : null;
    }

    private void record(String op, long nanos, boolean ok, String err) {
        if (!recording) {
            return;
        }
        Op o;
        synchronized (ops) {
            o = ops.computeIfAbsent(op, Op::new);
        }
        synchronized (o) {
            o.nanos.add(nanos);
            if (!ok) {
                o.errors++;
                if (o.firstError == null) {
                    o.firstError = err;
                }
            }
        }
    }

    private void error(String op, String message) {
        record(op, 0, false, message);
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("name=\"_csrf\" type=\"hidden\" value=\"([^\"]+)\"").matcher(page.body());
        if (!m.find()) {
            throw new IllegalStateException("No CSRF field on /login; is the host running with the dev profile?");
        }
        String pre = cookies(page);
        String form = "username=" + user + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", pre)
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        String after = cookies(r);
        String sid = cookie(after, "JSESSIONID");
        String xsrf = cookie(after, "XSRF-TOKEN");
        if (r.statusCode() != 302 || sid == null || xsrf == null) {
            throw new IllegalStateException("Sign-in of " + user + " failed: HTTP " + r.statusCode());
        }
        return new Session("JSESSIONID=" + sid + "; XSRF-TOKEN=" + xsrf, xsrf);
    }

    private static String cookies(HttpResponse<?> r) {
        StringBuilder sb = new StringBuilder();
        for (String h : r.headers().allValues("Set-Cookie")) {
            String nv = h.split(";", 2)[0];
            if (nv.endsWith("=")) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : "; ").append(nv);
        }
        return sb.toString();
    }

    private static String cookie(String all, String name) {
        for (String p : all.split("; ")) {
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private String id(String prefix) {
        return prefix + "-" + run + "-" + seq.incrementAndGet();
    }

    // ---- results ---------------------------------------------------------------------------------------------

    private String summary(Op op) {
        long[] v = op.nanos.stream().mapToLong(Long::longValue).filter(x -> x > 0).toArray();
        Arrays.sort(v);
        double secs = 0;
        for (long x : v) {
            secs += x;
        }
        return String.format(java.util.Locale.ROOT,
                "{ \"count\": %d, \"errors\": %d, \"perSecond\": %.1f, \"p50Ms\": %.2f, \"p95Ms\": %.2f, \"p99Ms\": %.2f, \"maxMs\": %.2f, "
                        + "\"meanMs\": %.2f, \"firstError\": %s }",
                op.nanos.size(), op.errors, (double) op.nanos.size() / measuredSeconds, pct(v, 50), pct(v, 95), pct(v, 99),
                v.length == 0 ? 0 : v[v.length - 1] / 1e6,
                v.length == 0 ? 0 : secs / v.length / 1e6, op.firstError == null ? "null" : q(op.firstError));
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1e6;
    }

    private String table() {
        StringBuilder sb = new StringBuilder(String.format("%-28s %8s %6s %9s %9s %9s %9s%n", "operation", "count",
                "errors", "p50 ms", "p95 ms", "p99 ms", "max ms"));
        for (Op op : ops.values()) {
            long[] v = op.nanos.stream().mapToLong(Long::longValue).filter(x -> x > 0).toArray();
            Arrays.sort(v);
            sb.append(String.format(java.util.Locale.ROOT, "%-28s %8d %6d %9.2f %9.2f %9.2f %9.2f%n", op.name,
                    op.nanos.size(), op.errors, pct(v, 50), pct(v, 95), pct(v, 99),
                    v.length == 0 ? 0 : v[v.length - 1] / 1e6));
        }
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                m.put(args[i].substring(2), args[++i]);
            }
        }
        return m;
    }
}
