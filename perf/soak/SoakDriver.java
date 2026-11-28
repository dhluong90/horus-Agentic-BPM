import java.io.IOException;
import java.net.URI;
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
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Soak driver (#655). No dependency beyond the JDK (Java 21 or later). Started by perf/soak/soak.sh against the stack in
 * docker-compose.soak.yml; see perf/soak/README.md.
 *
 * <p>It does five things at once and then judges the result:
 * <ol>
 * <li>Load: a steady mix of rule evaluations, card applications, task flows, message flows, timer flows and double
 *     submits through the load balancer, as the demo users, with HTTP Basic (a replica that dies takes no session
 *     with it). Every mutating call carries an Idempotency-Key and is replayed with the same key when a copy dies
 *     under it. Everything it did is written to a client ledger first.</li>
 * <li>Chaos: kills, restarts and stops one copy at a time and pauses PostgreSQL, on a fixed schedule (CI) or at random
 *     (long run), with the docker command line and the container names of the stack. A "freeze" pauses every process of a copy and resumes
 *     it later, so it comes back with its connections and its old work, as a stalled machine does.</li>
 * <li>Samples: memory, threads, file descriptors and database connections of every container, every few seconds.</li>
 * <li>Checkpoints: cheap database checks (duplicates, stuck timers) while the load runs.</li>
 * <li>Verdict: after the load, waits for every in-flight case to end, then compares the ledger with the database
 *     (nothing lost, nothing twice), replays requests with their keys, checks timers, seals, the decision log and
 *     the history, the error rate in and out of fault windows and the flatness of memory and connections.</li>
 * </ol>
 * The exit code is 0 when every check passed, 1 when one failed, 2 when the run could not be set up.
 */
public final class SoakDriver {

    private static final String RULES = "/api/bpm/rules/v1";
    private static final String PROCESSES = "/api/bpm/processes/v1";
    private static final Pattern ID = Pattern.compile("^\\{\"id\":\"([0-9A-Z]{26})\"");
    private static final Pattern INSTANCE_ID = Pattern.compile("\"instanceId\":\"([0-9A-Z]{26})\"");
    private static final Pattern DECISION_ID = Pattern.compile("\"decisionId\":\"([0-9A-Z]{26})\"");
    private static final Pattern FIRST_TASK = Pattern.compile("\"tasks\":\\[\\{\"id\":\"([0-9A-Z]{26})\".*?\"lockVersion\":(\\d+)");
    private static final Pattern LOCK = Pattern.compile("\"lockVersion\":(\\d+)");
    private static final long BUCKET_MS = 10_000;

    // ---- configuration ---------------------------------------------------------------------------------------

    private final Map<String, String> arg;
    private final String base;
    private final List<String> replicas;
    private final List<String> replicaNames;
    private final String password;
    private final String run;
    private final Path out;
    private final long seed;
    private final Random random;
    private final int loadSeconds;
    private final int drainSeconds;
    private final int workers;
    private final double flowsPerSecond;
    private final boolean randomChaos;
    private final boolean noChaos;
    private final int checkpointSeconds;
    private final int sampleSeconds;
    private final String prefix;
    private final String pgContainer = "horus-soak-postgres";
    private final String pooler = "horus-soak-pgbouncer";
    private final boolean pooled;
    private final double maxErrorRate;
    private final double maxQuietBucketErrorRate;
    private final boolean tamper;

    // ---- state -----------------------------------------------------------------------------------------------

    private final long t0 = System.currentTimeMillis();
    private final AtomicBoolean loading = new AtomicBoolean(true);
    private final AtomicLong slot = new AtomicLong();
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentLinkedQueue<Flow> ledger = new ConcurrentLinkedQueue<>();
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final Map<Long, long[]> buckets = new ConcurrentHashMap<>(); // bucket -> calls, attempt errors, final errors
    private final List<Fault> faults = Collections.synchronizedList(new ArrayList<>());
    private final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<Check> checks = new ArrayList<>();
    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> checkpointLog = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> auth = new HashMap<>();
    private volatile boolean faultActive;
    private int triggersAtStart;

    private SoakDriver(Map<String, String> a) throws IOException {
        this.arg = a;
        this.base = a.getOrDefault("base", "http://127.0.0.1:18091");
        this.replicas = List.of(a.getOrDefault("replicas",
                "http://127.0.0.1:18101,http://127.0.0.1:18102,http://127.0.0.1:18103").split(","));
        this.replicaNames = List.of("horus-soak-host1", "horus-soak-host2", "horus-soak-host3");
        this.password = Files.readString(Path.of(a.getOrDefault("password-file", "secrets/demo_password"))).strip();
        this.run = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        this.prefix = "SK-" + run + "-";
        this.out = Path.of(a.getOrDefault("out", "perf/results/soak"));
        this.seed = Long.parseLong(a.getOrDefault("seed", Long.toString(System.currentTimeMillis() % 100000)));
        this.random = new Random(seed);
        this.loadSeconds = Integer.parseInt(a.getOrDefault("duration", "600"));
        this.drainSeconds = Integer.parseInt(a.getOrDefault("drain", "240"));
        this.workers = Integer.parseInt(a.getOrDefault("workers", "12"));
        this.flowsPerSecond = Double.parseDouble(a.getOrDefault("flows-per-second", "10"));
        this.randomChaos = a.getOrDefault("chaos", "scheduled").equals("random");
        this.noChaos = a.getOrDefault("chaos", "scheduled").equals("none");
        this.checkpointSeconds = Integer.parseInt(a.getOrDefault("checkpoint", "120"));
        this.sampleSeconds = Integer.parseInt(a.getOrDefault("sample", "10"));
        this.pooled = !a.getOrDefault("pooler", "on").equals("off");
        this.maxErrorRate = Double.parseDouble(a.getOrDefault("max-error-rate", "0.005"));
        this.maxQuietBucketErrorRate = Double.parseDouble(a.getOrDefault("max-quiet-error-rate", "0.02"));
        this.tamper = a.getOrDefault("tamper", "off").equals("on");
        for (String u : List.of("tuan", "an", "hung", "lan", "minh", "hoa")) {
            auth.put(u, "Basic " + Base64.getEncoder().encodeToString((u + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        Files.createDirectories(out);
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                a.put(args[i].substring(2), args[++i]);
            }
        }
        SoakDriver d = new SoakDriver(a);
        System.exit(d.execute());
    }

    // ---- the run ---------------------------------------------------------------------------------------------

    private int execute() throws Exception {
        log("run " + run + ", seed " + seed + ", load " + loadSeconds + " s, " + workers + " workers, "
                + flowsPerSecond + " flows/s, chaos " + (noChaos ? "none" : randomChaos ? "random" : "scheduled") + ", pooler " + pooled);
        waitReady();
        triggersAtStart = Integer.parseInt(db("select count(*) from pg_trigger where not tgisinternal and tgenabled = 'O'").get(0)[0]);

        Thread sampler = Thread.ofPlatform().daemon().name("sampler").start(this::sampleLoop);
        Thread checkpoints = Thread.ofPlatform().daemon().name("checkpoints").start(this::checkpointLoop);
        Thread chaos = Thread.ofPlatform().name("chaos").start(this::chaosLoop);
        List<Thread> pool = new ArrayList<>();
        long endAt = System.currentTimeMillis() + loadSeconds * 1000L;
        for (int i = 0; i < workers; i++) {
            Worker w = new Worker(new Random(seed * 31 + i), endAt);
            pool.add(Thread.ofPlatform().name("worker-" + i).start(w));
        }
        for (Thread t : pool) {
            t.join();
        }
        loading.set(false);
        log("load stopped after " + (System.currentTimeMillis() - t0) / 1000 + " s; flows " + ledger.size());
        chaos.join();
        waitAllReplicasHealthy(120);

        if (tamper) {
            tamperWithData();
        }
        verify();
        sampler.interrupt();
        checkpoints.interrupt();
        sampleOnce(); // one last row
        return report();
    }

    private void waitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Reply r = once("tuan", "GET", base + "/api/bpm/meta", null, null, null, null);
                if (r.status == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("The load balancer at " + base + " did not answer /api/bpm/meta within 120 s");
    }

    // ---- load ------------------------------------------------------------------------------------------------

    /** One flow of the ledger: what the client meant to do and how far it got. */
    private static final class Flow {
        final String kind;
        final String key;
        final int total;
        volatile int acked;
        volatile boolean doubt;
        volatile String instanceId;
        volatile String variant = "";
        volatile String note = "";
        final long startedAt = System.currentTimeMillis();

        Flow(String kind, String key, int total) {
            this.kind = kind;
            this.key = key;
            this.total = total;
        }

        boolean done() {
            return acked >= total;
        }
    }

    private final class Worker implements Runnable {
        private final Random rnd;
        private final long endAt;
        private HttpClient client;
        private long clientBorn;

        Worker(Random rnd, long endAt) {
            this.rnd = rnd;
            this.endAt = endAt;
        }

        @Override
        public void run() {
            long interval = (long) (1e9 / flowsPerSecond); // global pacing: one flow starts every interval
            while (System.currentTimeMillis() < endAt) {
                long now = System.nanoTime();
                long mine = slot.updateAndGet(prev -> Math.max(now, prev + interval));
                long wait = mine - now;
                if (wait > 0) {
                    try {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (System.currentTimeMillis() >= endAt) {
                    return;
                }
                try {
                    flow(pick());
                } catch (Exception e) {
                    notes.add("worker error: " + e);
                }
            }
        }

        private String pick() {
            int n = rnd.nextInt(100);
            if (n < 34) {
                return "EVAL";
            } else if (n < 44) {
                return "CARD_GOLD";
            } else if (n < 60) {
                return "TASK_FLOW";
            } else if (n < 74) {
                return "MSG_FLOW";
            } else if (n < 88) {
                return "TIMER_FLOW";
            } else if (n < 93) {
                return "DOUBLE_SUBMIT";
            }
            return "READS";
        }

        /** Each worker has its own connection to the balancer and takes a new one now and then, so load follows the copies that are up. */
        private HttpClient http() {
            long now = System.currentTimeMillis();
            if (client == null || now - clientBorn > 20_000) {
                if (client != null) {
                    client.shutdownNow();
                }
                client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
                clientBorn = now;
            }
            return client;
        }

        private Reply call(Flow f, String op, String user, String method, String path, String body, String ifMatch, String idem) {
            return SoakDriver.this.call(http(), op, user, method, base + path, body, ifMatch, idem, idem);
        }

        private void flow(String kind) {
            String key = prefix + seq.incrementAndGet();
            switch (kind) {
                case "EVAL" -> {
                    Flow f = new Flow(kind, key, 1);
                    ledger.add(f);
                    String tier = rnd.nextInt(3) == 0 ? "GOLD" : "STANDARD";
                    Reply r = call(f, "rule.evaluate", "tuan", "POST", RULES + "/decisions/card-fee:evaluate",
                            "{\"inputs\":{\"tier\":\"" + tier + "\",\"txCount\":" + rnd.nextInt(9) + "}}", null, key);
                    ack(f, r, DECISION_ID);
                }
                case "CARD_GOLD" -> {
                    Flow f = new Flow(kind, key, 1);
                    ledger.add(f);
                    Reply r = call(f, "process.start", "tuan", "POST", "/api/cards/applications",
                            "{\"applicationId\":\"" + key + "\",\"customerId\":\"C-001\"}", null, null);
                    ack(f, r, INSTANCE_ID);
                }
                case "TASK_FLOW" -> {
                    Flow f = new Flow(kind, key, 3);
                    ledger.add(f);
                    Reply r = call(f, "process.start.to-user-task", "tuan", "POST", "/api/cards/applications",
                            "{\"applicationId\":\"" + key + "\",\"customerId\":\"C-002\"}", null, null);
                    if (!ack(f, r, INSTANCE_ID)) {
                        return;
                    }
                    Reply inst = call(f, "instance.read", "an", "GET", PROCESSES + "/instances/" + f.instanceId, null, null, null);
                    Matcher t = inst.ok() ? FIRST_TASK.matcher(inst.body) : null;
                    if (t == null || !t.find()) {
                        f.doubt = true;
                        return;
                    }
                    taskSteps(f, t.group(1), t.group(2), key);
                }
                case "MSG_FLOW" -> {
                    Flow f = new Flow(kind, key, 2);
                    ledger.add(f);
                    Reply r = call(f, "process.start.to-wait-state", "tuan", "POST", PROCESSES + "/instances",
                            "{\"definitionKey\":\"order-payment\",\"businessKey\":\"" + key + "\",\"variables\":{\"orderId\":\""
                                    + key + "\",\"amount\":\"100\"}}", null, key + "-st");
                    if (!ack(f, r, ID)) {
                        return;
                    }
                    Reply m = call(f, "message.correlate", "hung", "POST", PROCESSES + "/messages:correlate",
                            "{\"messageName\":\"payment-received\",\"correlationKey\":\"" + key + "\",\"payload\":{\"reference\":\"TT-"
                                    + key + "\"}}", null, key + "-mc");
                    step(f, m);
                }
                case "TIMER_FLOW" -> {
                    boolean message = rnd.nextInt(10) < 3;
                    Flow f = new Flow(kind, key, message ? 4 : 3);
                    f.variant = message ? "message" : "timer";
                    ledger.add(f);
                    Reply r = call(f, "process.start.to-timer", "tuan", "POST", PROCESSES + "/instances",
                            "{\"definitionKey\":\"payment-review\",\"businessKey\":\"" + key + "\",\"variables\":{\"paymentId\":\""
                                    + key + "\",\"riskScore\":10}}", null, key + "-st");
                    if (!ack(f, r, ID)) {
                        return;
                    }
                    Matcher t = FIRST_TASK.matcher(r.body);
                    if (!t.find()) {
                        f.doubt = true;
                        return;
                    }
                    if (!taskSteps(f, t.group(1), t.group(2), key) || !message) {
                        return;
                    }
                    Reply m = call(f, "message.correlate", "hung", "POST", PROCESSES + "/messages:correlate",
                            "{\"messageName\":\"payment-received\",\"correlationKey\":\"" + key + "\",\"payload\":{\"reference\":\"TT-"
                                    + key + "\"}}", null, key + "-mc");
                    step(f, m);
                }
                case "DOUBLE_SUBMIT" -> doubleSubmit(key);
                default -> {
                    call(null, "list.instances", "tuan", "GET", PROCESSES + "/instances?limit=50", null, null, null);
                    call(null, "list.tasks", "an", "GET", PROCESSES + "/tasks?limit=50", null, null, null);
                    call(null, "list.decisions", "minh", "GET", RULES + "/decisions?ruleSet=card-fee&from="
                            + java.net.URLEncoder.encode(Instant.now().minus(Duration.ofHours(1)).toString(), StandardCharsets.UTF_8)
                            + "&to=" + java.net.URLEncoder.encode(Instant.now().plus(Duration.ofMinutes(5)).toString(),
                            StandardCharsets.UTF_8) + "&limit=50", null, null, null);
                }
            }
        }

        /** Claim then complete a task as the officer; true when both were acknowledged. */
        private boolean taskSteps(Flow f, String task, String lock, String key) {
            Reply c = call(f, "task.claim", "an", "POST", PROCESSES + "/tasks/" + task + ":claim", null, "\"" + lock + "\"", key + "-cl");
            if (!step(f, c)) {
                return false;
            }
            Matcher l = LOCK.matcher(c.body);
            if (!l.find()) {
                f.doubt = true;
                return false;
            }
            Reply d = call(f, "task.complete", "an", "POST", PROCESSES + "/tasks/" + task + ":complete",
                    "{\"result\":{\"approved\":true}}", "\"" + l.group(1) + "\"", key + "-co");
            return step(f, d);
        }

        /** The same keyed start sent twice at once: both must answer with the same case, and one case must exist. */
        private void doubleSubmit(String key) {
            Flow f = new Flow("DOUBLE_SUBMIT", key, 1);
            ledger.add(f);
            String body = "{\"definitionKey\":\"order-payment\",\"businessKey\":\"" + key + "\",\"variables\":{\"orderId\":\"" + key
                    + "\",\"amount\":\"100\"}}";
            // The orders wait for a payment that never comes: they are the "left waiting by design" cases of the report.
            Reply[] r = new Reply[2];
            Thread other = Thread.ofVirtual().start(() -> {
                try (HttpClient c = newClient()) {
                    r[1] = SoakDriver.this.call(c, "process.start.double", "tuan", "POST", base + PROCESSES + "/instances", body, null,
                            key + "-ds", key + "-ds");
                }
            });
            r[0] = SoakDriver.this.call(http(), "process.start.double", "tuan", "POST", base + PROCESSES + "/instances", body, null,
                    key + "-ds", key + "-ds");
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Matcher a = r[0].ok() ? ID.matcher(r[0].body) : null;
            Matcher b = r[1] != null && r[1].ok() ? ID.matcher(r[1].body) : null;
            if (a != null && a.find() && b != null && b.find()) {
                f.instanceId = a.group(1);
                f.acked = 1;
                if (!a.group(1).equals(b.group(1))) {
                    f.note = "the two answers named different cases: " + a.group(1) + " and " + b.group(1);
                    f.doubt = true;
                    notes.add(key + ": " + f.note);
                }
            } else {
                f.doubt = true;
            }
        }

        private boolean ack(Flow f, Reply r, Pattern idPattern) {
            if (!r.ok()) {
                f.doubt = true;
                return false;
            }
            Matcher m = idPattern.matcher(r.body);
            if (m.find()) {
                if (idPattern != DECISION_ID) {
                    f.instanceId = m.group(1);
                }
                f.note = idPattern == DECISION_ID ? m.group(1) : "";
            }
            f.acked++;
            return true;
        }

        private boolean step(Flow f, Reply r) {
            if (!r.ok()) {
                f.doubt = true;
                return false;
            }
            f.acked++;
            return true;
        }
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }

    // ---- HTTP with replay ------------------------------------------------------------------------------------

    private record Reply(int status, String body, String error) {
        boolean ok() {
            return status / 100 == 2;
        }
    }

    private static final class Op {
        final String name;
        final List<Long> nanos = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong errors = new AtomicLong();
        final AtomicLong attemptErrors = new AtomicLong();
        volatile String firstError;

        Op(String name) {
            this.name = name;
        }
    }

    private Reply once(String user, String method, String url, String body, String ifMatch, String idem, String corr) throws Exception {
        try (HttpClient c = newClient()) {
            return send(c, user, method, url, body, ifMatch, idem, corr);
        }
    }

    private Reply send(HttpClient http, String user, String method, String url, String body, String ifMatch, String idem, String corr)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", auth.get(user)).header("Accept", "application/json");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        if (idem != null) {
            b.header("Idempotency-Key", idem);
        }
        if (corr != null) {
            b.header("X-Correlation-Id", corr);
        }
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), null);
    }

    /**
     * One logical call: replayed with the same key and the same body for up to 45 s when the copy that had it died, the
     * database was paused or the answer was a 5xx. A 409 is the "same key still running" answer and is replayed too.
     * The latency is that of the whole call, the way a caller sees it.
     */
    private Reply call(HttpClient http, String op, String user, String method, String url, String body, String ifMatch, String idem,
            String corr) {
        Op o = ops.computeIfAbsent(op, Op::new);
        long start = System.nanoTime();
        long deadline = System.currentTimeMillis() + 45_000;
        long[] bucket = buckets.computeIfAbsent((System.currentTimeMillis() - t0) / BUCKET_MS, k -> new long[3]);
        synchronized (bucket) {
            bucket[0]++;
        }
        int attempt = 0;
        Reply last;
        while (true) {
            attempt++;
            try {
                last = send(http, user, method, url, body, ifMatch, idem, corr);
            } catch (Exception e) {
                last = new Reply(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            boolean retry = last.status() == -1 || last.status() >= 500 || last.status() == 409 || last.status() == 408
                    || last.status() == 429;
            if (!last.ok()) {
                o.attemptErrors.incrementAndGet();
                long[] cur = buckets.computeIfAbsent((System.currentTimeMillis() - t0) / BUCKET_MS, k -> new long[3]);
                synchronized (cur) {
                    cur[1]++;
                }
            }
            if (last.ok() || !retry || System.currentTimeMillis() > deadline || (idem == null && !method.equals("GET")
                    && last.status() != -1 && last.status() < 500)) {
                break;
            }
            try {
                Thread.sleep(Math.min(2000, 250L << Math.min(attempt - 1, 3)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        o.nanos.add(System.nanoTime() - start);
        if (!last.ok()) {
            o.errors.incrementAndGet();
            if (o.firstError == null) {
                o.firstError = last.status() + " " + (last.error() != null ? last.error() : trim(last.body()));
            }
            long[] cur = buckets.computeIfAbsent((System.currentTimeMillis() - t0) / BUCKET_MS, k -> new long[3]);
            synchronized (cur) {
                cur[2]++;
            }
        }
        return last;
    }

    // ---- chaos -----------------------------------------------------------------------------------------------

    private record Fault(String action, String target, long startMs, long endMs, long healthyMs, String detail) { }

    private void chaosLoop() {
        try {
            if (noChaos) {
                return;
            }
            if (randomChaos) {
                randomChaos();
            } else {
                scheduledChaos();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            notes.add("chaos error: " + e);
        }
    }

    private void scheduledChaos() throws Exception {
        long load = loadSeconds * 1000L;
        // One fault at a time, each followed by a recovery of at least a minute; every copy is hit at least once.
        // "freeze" pauses every process of a copy (no CPU, no answers, connections stay open) and resumes it: a stalled copy that comes back.
        String[][] plan = {
                {"0.08", "kill", "horus-soak-host1", "10"},
                {"0.20", "restart", "horus-soak-host2", "0"},
                {"0.32", "pause-db", pgContainer, "8"},
                {"0.42", "kill", "horus-soak-host3", "10"},
                {"0.56", "freeze", "horus-soak-host2", "25"},
                {"0.70", "stop", "horus-soak-host1", "15"},
                {"0.84", "pause-db", pgContainer, "5"}};
        for (String[] p : plan) {
            long at = t0 + (long) (Double.parseDouble(p[0]) * load);
            sleepUntil(at);
            if (!loading.get() || System.currentTimeMillis() > t0 + load - 20_000) {
                break;
            }
            fault(p[1], p[2], Integer.parseInt(p[3]));
        }
    }

    private void randomChaos() throws Exception {
        long load = loadSeconds * 1000L;
        long next = t0 + 120_000 + random.nextInt(180_000);
        while (loading.get()) {
            sleepUntil(next);
            if (!loading.get() || System.currentTimeMillis() > t0 + load - 120_000) {
                break;
            }
            int n = random.nextInt(10);
            String host = replicaNames.get(random.nextInt(replicaNames.size()));
            if (n < 3) {
                fault("kill", host, 5 + random.nextInt(25));
            } else if (n < 5) {
                fault("restart", host, 0);
            } else if (n < 7) {
                fault("pause-db", pgContainer, 3 + random.nextInt(15));
            } else if (n < 8) {
                fault("freeze", host, 20 + random.nextInt(80));
            } else if (n < 9) {
                fault("stop", host, 5 + random.nextInt(25));
            } else {
                fault("restart-db", pgContainer, 0);
            }
            next = System.currentTimeMillis() + 300_000 + random.nextInt(600_000);
        }
    }

    private void fault(String action, String target, int seconds) throws Exception {
        long start = System.currentTimeMillis();
        String before = docker(10, "inspect", "-f", "{{.State.StartedAt}}", target).out.strip();
        faultActive = true;
        log("fault " + action + " " + target + " " + seconds + " s");
        switch (action) {
            case "kill" -> {
                docker(20, "kill", target);
                Thread.sleep(seconds * 1000L);
                docker(60, "start", target);
            }
            case "stop" -> {
                docker(60, "stop", "-t", "20", target);
                Thread.sleep(seconds * 1000L);
                docker(60, "start", target);
            }
            case "restart" -> docker(90, "restart", "-t", "20", target);
            case "pause-db", "freeze" -> {
                docker(20, "pause", target);
                Thread.sleep(seconds * 1000L);
                docker(20, "unpause", target);
            }
            case "restart-db" -> docker(90, "restart", "-t", "10", target);
            default -> throw new IllegalArgumentException(action);
        }
        long end = System.currentTimeMillis();
        // A copy that was killed, stopped or restarted is back only when it has a new start time and answers its health check.
        boolean newStart = action.equals("kill") || action.equals("stop") || action.equals("restart") || action.equals("restart-db");
        long healthy = action.equals("pause-db") ? end : waitHealthy(target, 150, newStart ? before : null);
        faults.add(new Fault(action, target, start - t0, end - t0, healthy - t0, seconds + " s"));
        log("recovered " + target + " " + (healthy - start) / 1000 + " s after the fault began");
        // Half a minute of undisturbed running before anything else is touched.
        Thread.sleep(30_000);
        faultActive = false;
    }

    private long waitHealthy(String container, int seconds, String notStartedAt) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Cmd c = docker(10, "inspect", "-f", "{{.State.Health.Status}}|{{.State.StartedAt}}", container);
            String[] hs = c.out.strip().split("\\|");
            if (hs[0].equals("healthy") && (notStartedAt == null || (hs.length > 1 && !hs[1].equals(notStartedAt)))) {
                return System.currentTimeMillis();
            }
            Thread.sleep(2000);
        }
        notes.add(container + " was not healthy " + seconds + " s after it was started");
        return System.currentTimeMillis();
    }

    private void waitAllReplicasHealthy(int seconds) throws Exception {
        for (String r : replicaNames) {
            waitHealthy(r, seconds, null);
        }
    }

    private void sleepUntil(long epochMs) throws InterruptedException {
        while (System.currentTimeMillis() < epochMs && loading.get()) {
            Thread.sleep(Math.min(1000, Math.max(1, epochMs - System.currentTimeMillis())));
        }
    }

    // ---- samples ---------------------------------------------------------------------------------------------

    private record Sample(long atMs, String container, String startedAt, long rssKb, int threads, int fds, int dbConns, double memMb,
            double cpuPct) { }

    private final AtomicInteger dbSampleFailures = new AtomicInteger();
    private final List<long[]> dbSeries = Collections.synchronizedList(new ArrayList<>()); // at, backends, active, idleInTx, maxIdleInTxSec

    private void sampleLoop() {
        try {
            while (true) {
                sampleOnce();
                Thread.sleep(sampleSeconds * 1000L);
            }
        } catch (InterruptedException ignored) {
            // stopped
        }
    }

    private void sampleOnce() {
        long at = System.currentTimeMillis() - t0;
        Map<String, double[]> stats = dockerStats();
        for (String c : replicaNames) {
            try {
                Cmd started = docker(10, "inspect", "-f", "{{.State.StartedAt}}|{{.State.Running}}", c);
                String[] sr = started.out.strip().split("\\|");
                if (sr.length < 2 || !sr[1].equals("true")) {
                    continue;
                }
                Cmd p = docker(10, "exec", c, "sh", "-c", "grep -E 'VmRSS|Threads' /proc/1/status | tr -s ' \\t' ' '; ls /proc/1/fd | wc -l;"
                        + " netstat -tn 2>/dev/null | awk '$6==\"ESTABLISHED\" && $5 ~ /:5432$/' | wc -l");
                long rss = 0;
                int threads = 0;
                int fds = 0;
                int conns = 0;
                List<String> lines = p.out.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
                for (String l : lines) {
                    if (l.startsWith("VmRSS:")) {
                        rss = Long.parseLong(l.split(" ")[1]);
                    } else if (l.startsWith("Threads:")) {
                        threads = Integer.parseInt(l.split(" ")[1]);
                    }
                }
                if (lines.size() >= 4) {
                    fds = Integer.parseInt(lines.get(lines.size() - 2));
                    conns = Integer.parseInt(lines.get(lines.size() - 1));
                }
                double[] st = stats.getOrDefault(c, new double[] {0, 0});
                samples.add(new Sample(at, c, sr[0], rss, threads, fds, conns, st[0], st[1]));
            } catch (Exception e) {
                // a container that is down or being killed has no sample
            }
        }
        for (String c : List.of(pgContainer, pooler, "horus-soak-lb")) {
            double[] st = stats.get(c);
            if (st != null) {
                samples.add(new Sample(at, c, "", 0, 0, 0, 0, st[0], st[1]));
            }
        }
        try {
            Cmd c = dockerRaw(12, "exec", pgContainer, "psql", "-U", "postgres", "-d", "horus", "-At", "-F", "|", "-c",
                    "select count(*), count(*) filter (where state = 'active'), count(*) filter (where state like 'idle in transaction%'),"
                            + " coalesce(max(extract(epoch from now() - xact_start)) filter (where state like 'idle in transaction%'), 0)::int"
                            + " from pg_stat_activity where datname = 'horus' and usename = 'horus'");
            if (c.code == 0 && !c.out.isBlank()) {
                String[] f = c.out.strip().split("\\|");
                dbSeries.add(new long[] {at, Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3])});
            } else {
                dbSampleFailures.incrementAndGet();
            }
        } catch (Exception e) {
            dbSampleFailures.incrementAndGet();
        }
    }

    private Map<String, double[]> dockerStats() {
        Map<String, double[]> m = new HashMap<>();
        try {
            Cmd c = docker(15, "stats", "--no-stream", "--format", "{{.Name}}|{{.MemUsage}}|{{.CPUPerc}}");
            for (String l : c.out.split("\n")) {
                String[] f = l.strip().split("\\|");
                if (f.length == 3 && f[0].startsWith("horus-soak")) {
                    m.put(f[0], new double[] {toMb(f[1].split("/")[0].strip()), Double.parseDouble(f[2].replace("%", ""))});
                }
            }
        } catch (Exception ignored) {
            // no stats this time
        }
        return m;
    }

    private static double toMb(String s) {
        Matcher m = Pattern.compile("([0-9.]+)\\s*([KMG]i?B|B)").matcher(s);
        if (!m.find()) {
            return 0;
        }
        double v = Double.parseDouble(m.group(1));
        return switch (m.group(2).charAt(0)) {
            case 'G' -> v * 1024;
            case 'K' -> v / 1024;
            case 'B' -> v / 1048576;
            default -> v;
        };
    }

    // ---- checkpoints and database access ---------------------------------------------------------------------

    private void checkpointLoop() {
        try {
            Thread.sleep(checkpointSeconds * 1000L);
            while (true) {
                if (!faultActive) {
                    try {
                        String r = lightChecks();
                        checkpointLog.add("t+" + (System.currentTimeMillis() - t0) / 1000 + " s: " + r);
                        log("checkpoint: " + r);
                    } catch (Exception e) {
                        checkpointLog.add("checkpoint could not run: " + e.getMessage());
                    }
                }
                Thread.sleep(checkpointSeconds * 1000L);
            }
        } catch (InterruptedException ignored) {
            // stopped
        }
    }

    private String lightChecks() {
        long dupInstances = count("select count(*) from (select business_key from pre_instance where business_key like '" + prefix
                + "%' group by 1 having count(*) > 1) x");
        long dupTasks = count("select count(*) from (select instance_id, node_id from pre_task where instance_id in (select id from"
                + " pre_instance where business_key like '" + prefix + "%') group by 1, 2 having count(*) > 1) x");
        long stuckTimers = count("select count(*) from pre_timer where status in ('DUE','CLAIMED') and next_fire_at < now() - interval"
                + " '3 minutes' and coalesce(locked_until, now() - interval '1 hour') < now() - interval '3 minutes'");
        return "duplicate cases " + dupInstances + ", duplicate tasks " + dupTasks + ", timers overdue 3 min " + stuckTimers;
    }

    private long count(String sql) {
        return Long.parseLong(db(sql).get(0)[0]);
    }

    private List<String[]> db(String sql) {
        Cmd c = dockerRaw(60, "exec", pgContainer, "psql", "-U", "postgres", "-d", "horus", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1",
                "-c", sql);
        if (c.code != 0) {
            throw new IllegalStateException("psql failed: " + trim(c.err) + " for " + trim(sql));
        }
        List<String[]> rows = new ArrayList<>();
        for (String l : c.out.split("\n")) {
            if (!l.isEmpty()) {
                rows.add(l.split("\\|", -1));
            }
        }
        return rows;
    }

    private record Cmd(int code, String out, String err) { }

    private Cmd docker(int timeoutSeconds, String... args) {
        return dockerRaw(timeoutSeconds, args);
    }

    private Cmd dockerRaw(int timeoutSeconds, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(Arrays.asList(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            // Read both streams while it runs, so a full pipe never blocks the command.
            var outF = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            var errF = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Cmd(124, "", "timed out after " + timeoutSeconds + " s");
            }
            return new Cmd(p.exitValue(), outF.get(5, TimeUnit.SECONDS), errF.get(5, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new Cmd(125, "", e.toString());
        }
    }

    private static String read(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }


    // ---- self-test: damage the data, the checks must notice --------------------------------------------------

    /** What the self-test breaks, and the check that must then fail. */
    private static final Set<String> TAMPER_EXPECTS = Set.of("LOST-INSTANCES", "DUPLICATES", "TIMERS-EXACTLY-ONCE", "AUDIT-TRAIL",
            "DECISION-LOG", "SEALS-SERVE");

    private void tamperWithData() {
        log("self-test: damaging the data on purpose");
        String like = "business_key like '" + prefix + "%'";
        // 1. A lost case: one acknowledged task case disappears (the database is told to ignore its own constraints for this one statement).
        String lost = db("select id from pre_instance where " + like + " and definition_key = 'card-fee-review' limit 1").get(0)[0];
        db("begin; set local session_replication_role = replica; delete from pre_task where instance_id = '" + lost
                + "'; delete from pre_token where instance_id = '" + lost + "'; delete from pre_instance where id = '" + lost + "'; commit;");
        // 2. (A second case with one business key is refused by the schema, uq_pre_instance_business_key, so it cannot be staged.)
        // 3. A timer that fired twice: one more TIMER_FIRED row in a history. A timer needs its minute first, so wait for one.
        for (int i = 0; i < 40 && count("select count(*) from pre_history h join pre_instance i on i.id = h.instance_id where i." + like
                + " and h.event_type = 'TIMER_FIRED'") == 0; i++) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        db("begin; create temp table th as select h.* from pre_history h join pre_instance i on i.id = h.instance_id where i." + like.replace("business_key", "business_key")
                + " and h.event_type = 'TIMER_FIRED' limit 1; update th set id = '01ZZZZZZZZZZZZZZZZZZZZZZZY'; insert into pre_history select * from th; commit;");
        // 4. A guard switched off.
        db("alter table pre_history_default disable trigger pre_history_no_change");
        // 5. A decision logged twice.
        db("begin; create temp table dd as select * from rle_decision where correlation_id like '" + prefix + "%' limit 1; update dd set id ="
                + " '01ZZZZZZZZZZZZZZZZZZZZZZZX'; insert into rle_decision select * from dd; commit;");
        // 6. A seal damaged on the approved version: well formed, so the schema accepts it, but not the hash of what was approved. A copy that
        // loads the version cold must refuse it (the engine checks the seal on load), so one copy is restarted.
        db("update rle_version set approval_seal = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'"
                + " where approved_at is not null");
        docker(90, "restart", "-t", "20", replicaNames.get(0));
        try {
            waitHealthy(replicaNames.get(0), 45, null);
        } catch (Exception e) {
            notes.add("self-test: " + e);
        }
    }

    private int selfTestVerdict() {
        Set<String> failed = new HashSet<>();
        for (Check c : checks) {
            if (!c.pass()) {
                failed.add(c.id());
            }
        }
        Set<String> missed = new HashSet<>(TAMPER_EXPECTS);
        missed.removeAll(failed);
        Set<String> unexpected = new HashSet<>(failed);
        unexpected.removeAll(TAMPER_EXPECTS);
        log("self-test: expected to fail " + TAMPER_EXPECTS + "; missed " + missed + "; failed beyond those " + unexpected);
        return missed.isEmpty() ? 0 : 1;
    }

    // ---- verdict ---------------------------------------------------------------------------------------------

    private record Check(String id, String title, boolean pass, String detail) { }

    private void check(String id, String title, boolean pass, String detail) {
        checks.add(new Check(id, title, pass, detail));
        log((pass ? "PASS " : "FAIL ") + id + " " + title + (detail.isEmpty() ? "" : " - " + detail));
    }

    private void verify() throws Exception {
        List<Flow> flows = new ArrayList<>(ledger);
        Map<String, Long> kinds = new TreeMap<>();
        for (Flow f : flows) {
            kinds.merge(f.kind, 1L, Long::sum);
        }
        log("verifying " + flows.size() + " flows " + kinds);

        // 1. Drain: every acknowledged flow must reach its end without help.
        long deadline = System.currentTimeMillis() + drainSeconds * 1000L;
        Map<String, String> st;
        while (true) {
            Map<String, String> now = instanceStatus();
            long open = flows.stream().filter(Flow::done).filter(f -> f.instanceId != null && expectsEnd(f))
                    .filter(f -> now.containsKey(f.key) && !"COMPLETED".equals(now.get(f.key))).count();
            st = now;
            if (open == 0 || System.currentTimeMillis() > deadline) {
                break;
            }
            log("waiting for " + open + " cases to end");
            Thread.sleep(10_000);
        }

        // C-LOST: every acknowledged start exists once.
        List<String> lost = new ArrayList<>();
        List<String> orphans = new ArrayList<>();
        Set<String> dbKeys = st.keySet();
        for (Flow f : flows) {
            if (f.instanceId != null && f.acked >= 1 && !f.kind.equals("EVAL") && !dbKeys.contains(f.key)) {
                lost.add(f.kind + " " + f.key + " " + f.instanceId);
            }
        }
        Set<String> ledgerKeys = new HashSet<>();
        for (Flow f : flows) {
            ledgerKeys.add(f.key);
        }
        for (String k : dbKeys) {
            if (!ledgerKeys.contains(k)) {
                orphans.add(k);
            }
        }
        Set<String> dbIds = new HashSet<>();
        for (String[] r : db("select id from pre_instance where business_key like '" + prefix + "%'")) {
            dbIds.add(r[0]);
        }
        for (Flow f : flows) {
            if (f.instanceId != null && f.acked >= 1 && !f.kind.equals("EVAL") && !dbIds.contains(f.instanceId)) {
                lost.add("id " + f.instanceId + " of " + f.key + " is not in pre_instance");
            }
        }
        long acked = flows.stream().filter(f -> f.acked >= 1 && f.instanceId != null).count();
        check("LOST-INSTANCES", "every acknowledged case exists", lost.isEmpty(),
                acked + " acknowledged starts, " + lost.size() + " missing" + sample(lost) + "; " + orphans.size() + " cases the ledger never sent");
        // A start that the client gave up on is allowed to exist once; one the ledger never sent is not.
        check("ORPHAN-INSTANCES", "no case the client never sent", orphans.isEmpty(), orphans.size() + sample(orphans));

        // C-DUP: nothing twice.
        long dupInst = count("select count(*) from (select business_key from pre_instance where business_key like '" + prefix
                + "%' group by 1 having count(*) > 1) x");
        long dupTask = count("select count(*) from (select instance_id, node_id from pre_task where instance_id in (select id from"
                + " pre_instance where business_key like '" + prefix + "%') group by 1, 2 having count(*) > 1) x");
        long dupDecision = count("select count(*) from (select correlation_id from rle_decision where correlation_id like '" + prefix
                + "%' group by 1 having count(*) > 1) x");
        long dupHistory = count("select count(*) from (select instance_id, event_type, node_id from pre_history where instance_id in (select id"
                + " from pre_instance where business_key like '" + prefix + "%') and event_type in ('INSTANCE_STARTED', 'INSTANCE_ENDED',"
                + " 'TIMER_SCHEDULED', 'TIMER_FIRED') group by 1, 2, 3 having count(*) > 1) x");
        long dupApp = count("select count(*) from (select application_id from card_application where application_id like '" + prefix
                + "%' group by 1 having count(*) > 1) x");
        check("DUPLICATES", "no case, task, decision, history event or application twice",
                dupInst + dupTask + dupDecision + dupHistory + dupApp == 0,
                "cases " + dupInst + ", tasks " + dupTask + ", decisions " + dupDecision + ", history events " + dupHistory
                        + ", applications " + dupApp);
        List<String> split = new ArrayList<>();
        for (Flow f : flows) {
            if (f.kind.equals("DOUBLE_SUBMIT") && !f.note.isEmpty()) {
                split.add(f.key + ": " + f.note);
            }
        }
        long ds = flows.stream().filter(f -> f.kind.equals("DOUBLE_SUBMIT")).count();
        check("DOUBLE-SUBMIT", "two parallel starts with one key give one case", split.isEmpty(), ds + " double submits, "
                + split.size() + " answered with two different cases" + sample(split));

        // C-DECISIONS: one decision row for each acknowledged evaluation, none for a key that was never sent.
        Map<String, Long> decisionRows = new HashMap<>();
        for (String[] r : db("select correlation_id, count(*) from rle_decision where correlation_id like '" + prefix + "%' group by 1")) {
            decisionRows.put(r[0], Long.parseLong(r[1]));
        }
        List<String> missingDecision = new ArrayList<>();
        long evalAcked = 0;
        for (Flow f : flows) {
            if (f.kind.equals("EVAL")) {
                if (f.acked >= 1) {
                    evalAcked++;
                    if (decisionRows.getOrDefault(f.key, 0L) != 1) {
                        missingDecision.add(f.key + "=" + decisionRows.getOrDefault(f.key, 0L));
                    }
                } else if (decisionRows.getOrDefault(f.key, 0L) > 1) {
                    missingDecision.add(f.key + "=" + decisionRows.get(f.key));
                }
            }
        }
        check("DECISION-LOG", "one decision log row for each acknowledged evaluation", missingDecision.isEmpty(),
                evalAcked + " acknowledged, " + missingDecision.size() + " with a wrong row count" + sample(missingDecision));
        long evalErrors = count("select count(*) from rle_decision where outcome = 'EVALUATION_ERROR' and created_at >= now() - interval '"
                + ((System.currentTimeMillis() - t0) / 1000 + 60) + " seconds'");
        check("SEALS", "no version refused for its seal, every approved version carries a valid seal", evalErrors == 0 && count(
                "select count(*) from rle_version where approved_at is not null and (approval_seal is null or approval_seal !~"
                        + " '^(sha256:[0-9a-f]{64}|hmac-sha256:[A-Za-z0-9_.-]{1,32}:[0-9a-f]{64}|legacy:pre-seal)$')") == 0,
                evalErrors + " evaluation errors in the decision log during the run");
        checkReplicasServe();

        // C-IDEMPOTENCY: replay a sample with the same key.
        replaySample(flows);

        // C-COMPLETE: in-flight cases end.
        completion(flows, st);

        // C-TIMERS.
        timers(flows);

        // C-HISTORY: the audit trail of every case is whole and the append-only guards are still on.
        history();

        // C-ERRORS and C-RESOURCES.
        errorRate();
        resources();
        chaosHappened();
        for (String cp : checkpointLog) {
            if (!cp.contains("duplicate cases 0, duplicate tasks 0, timers overdue 3 min 0") && !cp.startsWith("checkpoint could not run")) {
                check("CHECKPOINT", "checkpoint during the run", false, cp);
            }
        }
    }

    private boolean expectsEnd(Flow f) {
        return !f.kind.equals("DOUBLE_SUBMIT") && !f.kind.equals("EVAL");
    }

    private Map<String, String> instanceStatus() {
        Map<String, String> m = new HashMap<>();
        for (String[] r : db("select business_key, status from pre_instance where business_key like '" + prefix + "%'")) {
            m.put(r[0], r[1]);
        }
        return m;
    }

    private void checkReplicasServe() {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < replicas.size(); i++) {
            try {
                Reply r = SoakDriver.this.call(newClient(), "replica.evaluate", "tuan", "POST", replicas.get(i) + RULES
                        + "/decisions/card-fee:evaluate", "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":1}}", null, prefix + "final-" + i,
                        prefix + "final-" + i);
                if (!r.ok() || !r.body.contains("\"matched\":true")) {
                    bad.add(replicaNames.get(i) + " answered " + r.status + " " + trim(r.body));
                }
            } catch (Exception e) {
                bad.add(replicaNames.get(i) + " " + e);
            }
        }
        check("SEALS-SERVE", "every copy, after its restarts, loads the sealed version and serves it", bad.isEmpty(), bad.toString());
    }

    private void replaySample(List<Flow> flows) throws Exception {
        List<Flow> candidates = new ArrayList<>();
        for (Flow f : flows) {
            if (f.acked >= 1 && (f.kind.equals("EVAL") || f.kind.equals("MSG_FLOW") || f.kind.equals("TIMER_FLOW")
                    || f.kind.equals("DOUBLE_SUBMIT"))) {
                candidates.add(f);
            }
        }
        Collections.shuffle(candidates, random);
        int n = Math.min(150, candidates.size());
        long before = count("select count(*) from pre_instance where business_key like '" + prefix + "%'");
        long beforeDec = count("select count(*) from rle_decision where correlation_id like '" + prefix + "%'");
        List<String> bad = new ArrayList<>();
        HttpClient http = newClient();
        for (int i = 0; i < n; i++) {
            Flow f = candidates.get(i);
            String path;
            String body;
            String key;
            String user = "tuan";
            switch (f.kind) {
                case "EVAL" -> {
                    // The body was random; replay the evaluation with the same key and a body that differs: it must be refused.
                    Reply r = send(http, user, "POST", base + RULES + "/decisions/card-fee:evaluate",
                            "{\"inputs\":{\"tier\":\"PLATINUM-NOT-SENT\",\"txCount\":99}}", null, f.key, f.key);
                    if (r.status != 422 && r.status != 409) {
                        bad.add("key reuse with another body on " + f.key + " answered " + r.status);
                    }
                    continue;
                }
                case "DOUBLE_SUBMIT" -> {
                    path = PROCESSES + "/instances";
                    body = "{\"definitionKey\":\"order-payment\",\"businessKey\":\"" + f.key + "\",\"variables\":{\"orderId\":\"" + f.key
                            + "\",\"amount\":\"100\"}}";
                    key = f.key + "-ds";
                }
                case "MSG_FLOW" -> {
                    path = PROCESSES + "/instances";
                    body = "{\"definitionKey\":\"order-payment\",\"businessKey\":\"" + f.key + "\",\"variables\":{\"orderId\":\"" + f.key
                            + "\",\"amount\":\"100\"}}";
                    key = f.key + "-st";
                }
                default -> {
                    path = PROCESSES + "/instances";
                    body = "{\"definitionKey\":\"payment-review\",\"businessKey\":\"" + f.key + "\",\"variables\":{\"paymentId\":\"" + f.key
                            + "\",\"riskScore\":10}}";
                    key = f.key + "-st";
                }
            }
            Reply r = send(http, user, "POST", base + path, body, null, key, key);
            Matcher m = ID.matcher(r.body == null ? "" : r.body);
            if (!r.ok() || !m.find() || (f.instanceId != null && !m.group(1).equals(f.instanceId))) {
                bad.add("replay of " + key + " answered " + r.status + " " + trim(r.body));
            }
        }
        long after = count("select count(*) from pre_instance where business_key like '" + prefix + "%'");
        long afterDec = count("select count(*) from rle_decision where correlation_id like '" + prefix + "%'");
        check("IDEMPOTENT-REPLAY", "replaying acknowledged requests with their keys changes nothing",
                bad.isEmpty() && before == after && beforeDec == afterDec,
                n + " replays, cases " + before + " -> " + after + ", decisions " + beforeDec + " -> " + afterDec + sample(bad));
    }

    private void completion(List<Flow> flows, Map<String, String> st) throws Exception {
        List<String> stuck = new ArrayList<>();
        List<Flow> partial = new ArrayList<>();
        long full = 0;
        for (Flow f : flows) {
            if (!expectsEnd(f) || f.instanceId == null) {
                continue;
            }
            String s = st.get(f.key);
            if (f.done()) {
                full++;
                if (!"COMPLETED".equals(s)) {
                    stuck.add(f.kind + " " + f.key + " " + s);
                }
            } else if (s != null && !"COMPLETED".equals(s)) {
                partial.add(f);
            }
        }
        check("IN-FLIGHT-COMPLETE", "every case whose steps were all acknowledged completed by itself", stuck.isEmpty(),
                full + " cases, " + stuck.size() + " not completed after " + drainSeconds + " s" + sample(stuck));
        // Cases the client abandoned half way (its own retries ran out): an operator finishes them, and they must finish.
        int recovered = 0;
        List<String> failed = new ArrayList<>();
        for (Flow f : partial) {
            if (resume(f)) {
                recovered++;
            } else {
                failed.add(f.kind + " " + f.key);
            }
        }
        check("ABANDONED-RECOVERABLE", "a case the client abandoned can be finished by the operator", failed.isEmpty(),
                partial.size() + " abandoned half way, " + recovered + " finished, " + failed.size() + " could not be" + sample(failed));
    }

    /** Finish the next steps of a half-done case from what the database says is open. */
    private boolean resume(Flow f) throws Exception {
        for (int round = 0; round < 6; round++) {
            List<String[]> tasks = db("select t.id, t.status, t.lock_version from pre_task t join pre_instance i on i.id = t.instance_id"
                    + " where i.business_key = '" + f.key + "' and t.status in ('OPEN','CLAIMED')");
            HttpClient http = newClient();
            for (String[] t : tasks) {
                long lock = Long.parseLong(t[2]);
                if (t[1].equals("OPEN")) {
                    Reply c = call(http, "recover.claim", "an", "POST", base + PROCESSES + "/tasks/" + t[0] + ":claim", null,
                            "\"" + lock + "\"", f.key + "-rcl" + round, f.key + "-rcl" + round);
                    Matcher l = LOCK.matcher(c.body);
                    lock = l.find() ? Long.parseLong(l.group(1)) : lock + 1;
                }
                call(http, "recover.complete", "an", "POST", base + PROCESSES + "/tasks/" + t[0] + ":complete",
                        "{\"result\":{\"approved\":true}}", "\"" + lock + "\"", f.key + "-rco" + round, f.key + "-rco" + round);
            }
            String s = db("select status from pre_instance where business_key = '" + f.key + "'").get(0)[0];
            if (s.equals("COMPLETED")) {
                return true;
            }
            if (f.kind.equals("MSG_FLOW") || "message".equals(f.variant)) {
                call(http, "recover.correlate", "hung", "POST", base + PROCESSES + "/messages:correlate",
                        "{\"messageName\":\"payment-received\",\"correlationKey\":\"" + f.key + "\",\"payload\":{\"reference\":\"TT-"
                                + f.key + "\"}}", null, f.key + "-rmc" + round, f.key + "-rmc" + round);
            }
            Thread.sleep(15_000);
        }
        return db("select status from pre_instance where business_key = '" + f.key + "'").get(0)[0].equals("COMPLETED");
    }

    private void timers(List<Flow> flows) {
        Map<String, Long> fired = new HashMap<>();
        Map<String, Long> scheduled = new HashMap<>();
        for (String[] r : db("select i.business_key, count(*) filter (where h.event_type = 'TIMER_FIRED'), count(*) filter (where h.event_type"
                + " = 'TIMER_SCHEDULED') from pre_history h join pre_instance i on i.id = h.instance_id where i.business_key like '"
                + prefix + "%' and i.definition_key = 'payment-review' group by 1")) {
            fired.put(r[0], Long.parseLong(r[1]));
            scheduled.put(r[0], Long.parseLong(r[2]));
        }
        Map<String, String> timerRow = new HashMap<>();
        for (String[] r : db("select i.business_key, string_agg(t.status || ':' || t.attempt, ',') from pre_timer t join pre_instance i on i.id"
                + " = t.instance_id where i.business_key like '" + prefix + "%' and i.definition_key = 'payment-review' and t.job_kind"
                + " = 'TIMER' group by 1")) {
            timerRow.put(r[0], r[1]);
        }
        Map<String, String> ends = new HashMap<>();
        for (String[] r : db("select i.business_key, string_agg(h.node_id, ',') from pre_history h join pre_instance i on i.id = h.instance_id"
                + " where i.business_key like '" + prefix + "%' and i.definition_key = 'payment-review' and h.event_type = 'NODE_ENTERED'"
                + " and h.node_id in ('expired', 'settled', 'refunded') group by 1")) {
            ends.put(r[0], r[1]);
        }
        List<String> bad = new ArrayList<>();
        int viaTimer = 0;
        int viaMessage = 0;
        int redelivered = 0;
        for (Flow f : flows) {
            if (!f.kind.equals("TIMER_FLOW") || !f.done()) {
                continue;
            }
            long fi = fired.getOrDefault(f.key, 0L);
            long sc = scheduled.getOrDefault(f.key, 0L);
            String end = ends.getOrDefault(f.key, "");
            String row = timerRow.getOrDefault(f.key, "");
            if (row.matches(".*:[2-9]\\d*.*")) {
                redelivered++;
            }
            if (f.variant.equals("timer")) {
                viaTimer++;
                if (fi != 1 || sc != 1 || !end.equals("expired") || !row.startsWith("DONE:") || row.contains(",")) {
                    bad.add(f.key + " fired=" + fi + " scheduled=" + sc + " end=" + end + " timer=" + row);
                }
            } else {
                viaMessage++;
                if (fi != 0 || !end.equals("settled")) {
                    bad.add(f.key + " (message) fired=" + fi + " end=" + end + " timer=" + row);
                }
            }
        }
        long failedTimers = count("select count(*) from pre_timer where status = 'FAILED'");
        long overdue = count("select count(*) from pre_timer where status in ('DUE','CLAIMED') and next_fire_at < now() - interval '2 minutes'");
        check("TIMERS-EXACTLY-ONCE", "each timer fired once, in its own case, and a message that won cancelled it",
                bad.isEmpty() && failedTimers == 0 && overdue == 0,
                viaTimer + " timer cases, " + viaMessage + " message cases, " + redelivered + " timers re-leased after a copy died, "
                        + failedTimers + " failed timers, " + overdue + " overdue" + sample(bad));
    }

    private void history() {
        long broken = count("with e as (select h.instance_id, count(*) filter (where h.event_type = 'INSTANCE_STARTED') s, count(*) filter"
                + " (where h.event_type = 'INSTANCE_ENDED') en, max(h.occurred_at) filter (where h.event_type = 'INSTANCE_ENDED') ended,"
                + " max(h.occurred_at) last, min(h.occurred_at) filter (where h.event_type = 'INSTANCE_STARTED') started,"
                + " min(h.occurred_at) first from pre_history h where h.instance_id in (select id from pre_instance where business_key like '"
                + prefix + "%') group by 1) select count(*) from e join pre_instance i on i.id = e.instance_id where e.s <> 1 or e.en > 1"
                + " or (i.status = 'COMPLETED' and e.en <> 1) or (i.status = 'RUNNING' and e.en <> 0) or (e.ended is not null and e.last > e.ended)"
                + " or e.first < e.started");
        long noHistory = count("select count(*) from pre_instance i where i.business_key like '" + prefix + "%' and not exists (select 1 from"
                + " pre_history h where h.instance_id = i.id)");
        long triggers = count("select count(*) from pg_trigger where not tgisinternal and tgenabled = 'O'");
        long disabled = count("select count(*) from pg_trigger where not tgisinternal and tgenabled <> 'O'");
        long otherTenant = count("select count(*) from pre_instance where business_key like '" + prefix + "%' and tenant_id <> 'bank-a'")
                + count("select count(*) from rle_decision where correlation_id like '" + prefix + "%' and tenant_id <> 'bank-a'");
        check("AUDIT-TRAIL", "each case has one start, at most one end, nothing after the end; the append-only guards are on",
                broken == 0 && noHistory == 0 && disabled == 0 && triggers >= triggersAtStart,
                broken + " cases with a broken history, " + noHistory + " without history, " + triggers + " guards on (" + triggersAtStart
                        + " at the start), " + disabled + " disabled");
        check("TENANT-ISOLATION", "nothing of the run landed in another tenant", otherTenant == 0, otherTenant + " rows");
    }

    private void errorRate() {
        long calls = 0;
        long fin = 0;
        long attemptErr = 0;
        for (Op o : ops.values()) {
            calls += o.nanos.size();
            fin += o.errors.get();
            attemptErr += o.attemptErrors.get();
        }
        double rate = calls == 0 ? 0 : (double) fin / calls;
        check("ERROR-RATE", "final error rate after replays at most " + pct(maxErrorRate), rate <= maxErrorRate,
                fin + " of " + calls + " calls failed after replays (" + pct(rate) + "); " + attemptErr + " attempts failed on the way");
        // Buckets outside a fault window (and 30 s after it) must be quiet.
        List<String> loud = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : new TreeMap<>(buckets).entrySet()) {
            long from = e.getKey() * BUCKET_MS;
            long to = from + BUCKET_MS;
            boolean inWindow = false;
            synchronized (faults) {
                for (Fault f : faults) {
                    if (to > f.startMs() - 1000 && from < f.healthyMs() + 30_000) {
                        inWindow = true;
                    }
                }
            }
            long[] v = e.getValue();
            if (!inWindow && v[0] >= 20 && (double) v[1] / v[0] > maxQuietBucketErrorRate) {
                loud.add("t+" + from / 1000 + " s: " + v[1] + " failed attempts in " + v[0] + " calls");
            }
        }
        check("NO-ERROR-SPIKE", "no failed attempts above " + pct(maxQuietBucketErrorRate) + " in any 10 s outside a fault window",
                loud.isEmpty(), loud.size() + " loud buckets" + sample(loud));
    }

    private void resources() {
        List<String> bad = new ArrayList<>();
        List<String> info = new ArrayList<>();
        for (String c : replicaNames) {
            Map<String, List<Sample>> byEpoch = new LinkedHashMap<>();
            synchronized (samples) {
                for (Sample s : samples) {
                    if (s.container().equals(c) && s.rssKb() > 0) {
                        byEpoch.computeIfAbsent(s.startedAt(), k -> new ArrayList<>()).add(s);
                    }
                }
            }
            for (List<Sample> ep : byEpoch.values()) {
                if (ep.size() < 12) {
                    continue;
                }
                // Skip the first quarter (JIT, caches, pools filling); compare the middle half with the last quarter, by median.
                int n = ep.size();
                List<Sample> early = ep.subList(n / 4, n / 2);
                List<Sample> late = ep.subList(n * 3 / 4, n);
                double rssEarly = median(early.stream().mapToDouble(s -> s.rssKb() / 1024.0).toArray());
                double rssLate = median(late.stream().mapToDouble(s -> s.rssKb() / 1024.0).toArray());
                double fdEarly = median(early.stream().mapToDouble(Sample::fds).toArray());
                double fdLate = median(late.stream().mapToDouble(Sample::fds).toArray());
                double thEarly = median(early.stream().mapToDouble(Sample::threads).toArray());
                double thLate = median(late.stream().mapToDouble(Sample::threads).toArray());
                double cnEarly = median(early.stream().mapToDouble(Sample::dbConns).toArray());
                double cnLate = median(late.stream().mapToDouble(Sample::dbConns).toArray());
                double cnMax = ep.stream().mapToDouble(Sample::dbConns).max().orElse(0);
                info.add(String.format(Locale.ROOT, "%s (%d samples): RSS %.0f -> %.0f MB, threads %.0f -> %.0f, fds %.0f -> %.0f, db conns %.0f -> %.0f (max %.0f)",
                        c.replace("horus-soak-", ""), n, rssEarly, rssLate, thEarly, thLate, fdEarly, fdLate, cnEarly, cnLate, cnMax));
                if (rssLate > rssEarly * 1.20 && rssLate - rssEarly > 64) {
                    bad.add(c + " RSS " + (long) rssEarly + " -> " + (long) rssLate + " MB");
                }
                if (fdLate > fdEarly * 1.25 + 20) {
                    bad.add(c + " file descriptors " + (long) fdEarly + " -> " + (long) fdLate);
                }
                if (thLate > thEarly * 1.25 + 10) {
                    bad.add(c + " threads " + (long) thEarly + " -> " + (long) thLate);
                }
                if (cnLate > cnEarly + 5 || cnMax > 30) {
                    bad.add(c + " connections to the pooler " + (long) cnEarly + " -> " + (long) cnLate + " (max " + (long) cnMax + ")");
                }
            }
        }
        // Database: connections and transactions left open.
        if (dbSeries.size() >= 6) {
            int n = dbSeries.size();
            double early = median(dbSeries.subList(n / 4, n / 2).stream().mapToDouble(s -> s[1]).toArray());
            double late = median(dbSeries.subList(n * 3 / 4, n).stream().mapToDouble(s -> s[1]).toArray());
            double max = dbSeries.stream().mapToDouble(s -> s[1]).max().orElse(0);
            double idleTx = dbSeries.stream().mapToDouble(s -> s[4]).max().orElse(0);
            info.add(String.format(Locale.ROOT, "postgres backends of the application role: %.0f -> %.0f (max %.0f, limit %s), longest idle-in-transaction %.0f s, %d samples missed",
                    early, late, max, pooled ? "20 server connections" : "10 per copy", idleTx, dbSampleFailures.get()));
            // The pooler opens and closes server connections as the load changes, so a start-to-end trend means nothing here:
            // a leak shows as more backends than the pooler's cap, or as a transaction left open.
            if (max > (pooled ? 22 : 40)) {
                bad.add("postgres backends reached " + (long) max + " (cap " + (pooled ? "20" : "10 per copy") + ")");
            }
            if (idleTx > 60) {
                bad.add("a transaction idle for " + (long) idleTx + " s");
            }
        }
        for (String c : List.of(pgContainer, pooler, "horus-soak-lb")) {
            List<Sample> s;
            synchronized (samples) {
                s = samples.stream().filter(x -> x.container().equals(c)).toList();
            }
            if (s.size() >= 12) {
                int n = s.size();
                double early = median(s.subList(n / 4, n / 2).stream().mapToDouble(Sample::memMb).toArray());
                double late = median(s.subList(n * 3 / 4, n).stream().mapToDouble(Sample::memMb).toArray());
                info.add(String.format(Locale.ROOT, "%s memory %.0f -> %.0f MB", c.replace("horus-soak-", ""), early, late));
                // PostgreSQL's memory (page cache included) follows the data written, so only a runaway counts for it.
                double factor = c.equals(pgContainer) ? 2.0 : 1.25;
                if (late > early * factor && late - early > (c.equals(pgContainer) ? 256 : 64)) {
                    bad.add(c + " memory " + (long) early + " -> " + (long) late + " MB");
                }
            }
        }
        for (String c : replicaNames) {
            Cmd o = docker(10, "inspect", "-f", "{{.State.OOMKilled}}", c);
            if (o.out.strip().equals("true")) {
                bad.add(c + " was killed for memory");
            }
        }
        resourceInfo = info;
        check("RESOURCES-FLAT", "memory, threads, file descriptors and connections stay flat across each copy's life", bad.isEmpty(),
                bad.isEmpty() ? "see the table" : String.join("; ", bad));
    }

    private volatile List<String> resourceInfo = List.of();

    private void chaosHappened() {
        Set<String> hit = new HashSet<>();
        boolean dbPaused = false;
        synchronized (faults) {
            for (Fault f : faults) {
                hit.add(f.target());
                dbPaused |= f.action().equals("pause-db");
            }
        }
        if (noChaos) {
            check("CHAOS-RAN", "faults were switched off for this run (self-test)", true, "no faults");
            return;
        }
        boolean allReplicas = hit.containsAll(replicaNames);
        check("CHAOS-RAN", "every copy was killed, restarted or stopped and the database was paused",
                dbPaused && (allReplicas || randomChaos), faults.size() + " faults on " + hit);
        for (String c : replicaNames) {
            Cmd h = docker(10, "inspect", "-f", "{{.State.Health.Status}}", c);
            if (!h.out.strip().equals("healthy")) {
                check("REPLICA-HEALTHY", c + " healthy at the end", false, h.out.strip());
            }
        }
    }

    // ---- report ----------------------------------------------------------------------------------------------

    private int report() throws IOException {
        boolean pass = checks.stream().allMatch(Check::pass);
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        StringBuilder md = new StringBuilder();
        md.append("# Soak report ").append(run).append("\n\n");
        md.append(tamper ? "Self-test with damaged data: the checks " + TAMPER_EXPECTS + " must FAIL; the exit code is 0 when they do.\n\n" : "").append("Verdict: **").append(pass ? "PASS" : "FAIL").append("** (").append(checks.stream().filter(Check::pass).count()).append(" of ")
                .append(checks.size()).append(" checks)\n\n");
        md.append("| Setting | Value |\n|---|---|\n");
        md.append("| Run | ").append(run).append(", seed ").append(seed).append(" |\n");
        md.append("| Started | ").append(Instant.ofEpochMilli(t0)).append(" |\n");
        md.append("| Load | ").append(loadSeconds).append(" s, ").append(workers).append(" workers, ").append(flowsPerSecond).append(" flows/s target |\n");
        md.append("| Whole run | ").append(elapsed).append(" s including the drain and the checks |\n");
        md.append("| Stack | ").append(replicas.size()).append(" copies of rule-and-process behind a layer 4 balancer, PostgreSQL 16")
                .append(pooled ? " behind PgBouncer (transaction mode)" : " (direct)").append(" |\n");
        md.append("| Chaos | ").append(noChaos ? "none" : randomChaos ? "random" : "scheduled").append(", ").append(faults.size()).append(" faults |\n\n");
        md.append("## Checks\n\n| Check | Result | Detail |\n|---|---|---|\n");
        for (Check c : checks) {
            md.append("| ").append(c.id()).append(": ").append(c.title()).append(" | ").append(c.pass() ? "PASS" : "**FAIL**").append(" | ")
                    .append(c.detail().replace("|", "/")).append(" |\n");
        }
        md.append("\n## Faults\n\n| At (s) | Action | Target | Length | Back in service (s after the start) |\n|---:|---|---|---|---:|\n");
        synchronized (faults) {
            for (Fault f : faults) {
                md.append("| ").append(f.startMs() / 1000).append(" | ").append(f.action()).append(" | ").append(f.target().replace("horus-soak-", ""))
                        .append(" | ").append(f.detail()).append(" | ").append((f.healthyMs() - f.startMs()) / 1000).append(" |\n");
            }
        }
        md.append("\n## Operations\n\nLatency is that of the whole call, replays included.\n\n");
        md.append("| Operation | Calls | Failed after replays | Failed attempts | p50 ms | p95 ms | p99 ms | max ms |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Op o : new TreeMap<>(ops).values()) {
            long[] v = o.nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.1f | %.1f | %.1f | %.1f |%n", o.name, v.length, o.errors.get(),
                    o.attemptErrors.get(), pct(v, 50), pct(v, 95), pct(v, 99), v.length == 0 ? 0 : v[v.length - 1] / 1e6));
        }
        Map<String, Long> kinds = new TreeMap<>();
        long inDoubt = 0;
        for (Flow f : ledger) {
            kinds.merge(f.kind, 1L, Long::sum);
            if (f.doubt) {
                inDoubt++;
            }
        }
        md.append("\n## Ledger\n\n").append(ledger.size()).append(" flows ").append(kinds).append("; ").append(inDoubt)
                .append(" flows ended with a call that failed after its replays.\n");
        md.append("\n## Resources\n\n");
        for (String l : resourceInfo) {
            md.append("- ").append(l).append("\n");
        }
        if (!checkpointLog.isEmpty()) {
            md.append("\n## Checkpoints during the load\n\n");
            for (String c : checkpointLog) {
                md.append("- ").append(c).append("\n");
            }
        }
        if (!notes.isEmpty()) {
            md.append("\n## Notes\n\n");
            for (String n : notes.stream().distinct().limit(40).toList()) {
                md.append("- ").append(n).append("\n");
            }
        }
        Files.writeString(out.resolve("soak-report.md"), md.toString());

        StringBuilder csv = new StringBuilder("atSeconds,container,startedAt,rssKb,threads,fds,dbConns,memMb,cpuPct\n");
        synchronized (samples) {
            for (Sample s : samples) {
                csv.append(s.atMs() / 1000).append(',').append(s.container()).append(',').append(s.startedAt()).append(',').append(s.rssKb())
                        .append(',').append(s.threads()).append(',').append(s.fds()).append(',').append(s.dbConns()).append(',')
                        .append(String.format(Locale.ROOT, "%.1f,%.1f%n", s.memMb(), s.cpuPct()));
            }
        }
        Files.writeString(out.resolve("samples.csv"), csv.toString());

        StringBuilder bucketCsv = new StringBuilder("atSeconds,calls,failedAttempts,failedAfterReplays\n");
        for (Map.Entry<Long, long[]> e : new TreeMap<>(buckets).entrySet()) {
            bucketCsv.append(e.getKey() * BUCKET_MS / 1000).append(',').append(e.getValue()[0]).append(',').append(e.getValue()[1]).append(',')
                    .append(e.getValue()[2]).append('\n');
        }
        Files.writeString(out.resolve("errors-by-10s.csv"), bucketCsv.toString());

        StringBuilder j = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"run\": " + q(run) + ",\n  \"pass\": " + pass
                + ",\n  \"startedAt\": " + q(Instant.ofEpochMilli(t0).toString()) + ",\n  \"loadSeconds\": " + loadSeconds
                + ",\n  \"elapsedSeconds\": " + elapsed + ",\n  \"flows\": " + ledger.size() + ",\n  \"checks\": [");
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            j.append(i == 0 ? "\n" : ",\n").append("    { \"id\": ").append(q(c.id())).append(", \"pass\": ").append(c.pass())
                    .append(", \"detail\": ").append(q(c.detail())).append(" }");
        }
        j.append("\n  ],\n  \"operations\": {");
        boolean first = true;
        for (Op o : new TreeMap<>(ops).values()) {
            long[] v = o.nanos.stream().mapToLong(Long::longValue).sorted().toArray();
            j.append(first ? "\n" : ",\n").append("    ").append(q(o.name)).append(String.format(Locale.ROOT,
                    ": { \"count\": %d, \"errors\": %d, \"p50Ms\": %.2f, \"p95Ms\": %.2f, \"p99Ms\": %.2f, \"firstError\": %s }", v.length,
                    o.errors.get(), pct(v, 50), pct(v, 95), pct(v, 99), o.firstError == null ? "null" : q(o.firstError)));
            first = false;
        }
        j.append("\n  }\n}\n");
        Files.writeString(out.resolve("soak-report.json"), j.toString());
        Files.writeString(out.resolve("ledger.csv"), ledgerCsv());
        System.out.println();
        System.out.println(md);
        log("report written to " + out.toAbsolutePath());
        if (tamper) {
            return selfTestVerdict();
        }
        return pass ? 0 : 1;
    }

    private String ledgerCsv() {
        StringBuilder sb = new StringBuilder("kind,key,instanceId,acked,total,doubt,variant\n");
        for (Flow f : ledger) {
            sb.append(f.kind).append(',').append(f.key).append(',').append(f.instanceId == null ? "" : f.instanceId).append(',').append(f.acked)
                    .append(',').append(f.total).append(',').append(f.doubt).append(',').append(f.variant).append('\n');
        }
        return sb.toString();
    }

    // ---- small helpers ---------------------------------------------------------------------------------------

    private static double median(double[] v) {
        if (v.length == 0) {
            return 0;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))] / 1e6;
    }

    private static String pct(double rate) {
        return String.format(Locale.ROOT, "%.3f%%", rate * 100);
    }

    private static String sample(List<String> l) {
        return l.isEmpty() ? "" : " (first: " + String.join("; ", l.subList(0, Math.min(3, l.size()))) + ")";
    }

    private static String trim(String s) {
        return s == null ? "" : s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private void log(String m) {
        System.out.println(String.format(Locale.ROOT, "[t+%4ds] %s", (System.currentTimeMillis() - t0) / 1000, m));
    }
}
