import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Layer 4 load balancer of the soak stack (#655): the shape of a cloud network load balancer. It takes a TCP connection,
 * gives it to the next healthy backend in turn and copies bytes both ways. It never reads or replays a request, so a
 * request that was cut by a killed copy reaches the client as a reset, and the client replays it with its
 * Idempotency-Key. Health: GET /actuator/health every second; two failures take a backend out, two successes bring it
 * back; a refused connect takes it out at once. Names are resolved again for every connect (a restarted copy may
 * have a new address).
 *
 *   java -cp perf/results/soak-lb SoakLb --listen 8080 --stats 8404 --backend host1:8080 --backend host2:8080
 *
 * Stats: GET /stats on the stats port (JSON), GET /healthz. No dependency beyond the JDK (Java 21).
 */
public final class SoakLb {

    private static final class Backend {
        final String host;
        final int port;
        volatile boolean up;
        int fails;
        int passes;
        final AtomicLong connections = new AtomicLong();
        final AtomicLong connectFailures = new AtomicLong();
        final AtomicLong transitions = new AtomicLong();

        Backend(String spec) {
            int i = spec.lastIndexOf(':');
            this.host = spec.substring(0, i);
            this.port = Integer.parseInt(spec.substring(i + 1));
        }
    }

    private static final List<Backend> BACKENDS = new ArrayList<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong NO_BACKEND = new AtomicLong();

    public static void main(String[] args) throws Exception {
        java.security.Security.setProperty("networkaddress.cache.ttl", "1");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        int listen = 8080;
        int stats = 8404;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--listen" -> listen = Integer.parseInt(args[++i]);
                case "--stats" -> stats = Integer.parseInt(args[++i]);
                case "--backend" -> BACKENDS.add(new Backend(args[++i]));
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (BACKENDS.isEmpty()) {
            throw new IllegalArgumentException("Give at least one --backend host:port");
        }
        Thread.ofPlatform().daemon().name("health").start(SoakLb::healthLoop);
        int statsPort = stats;
        Thread.ofPlatform().daemon().name("stats").start(() -> serveStats(statsPort));
        try (ServerSocket server = new ServerSocket(listen, 512)) {
            System.out.println("soak-lb listening on " + listen + " for " + BACKENDS.size() + " backends");
            while (true) {
                Socket client = server.accept();
                ACCEPTED.incrementAndGet();
                Thread.ofVirtual().start(() -> handle(client));
            }
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            for (int attempt = 0; attempt < BACKENDS.size() && upstream == null; attempt++) {
                Backend b = pick();
                if (b == null) {
                    break;
                }
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(b.host, b.port), 1000);
                    s.setTcpNoDelay(true);
                    upstream = s;
                    b.connections.incrementAndGet();
                } catch (IOException e) {
                    b.connectFailures.incrementAndGet();
                    mark(b, false, true);
                }
            }
            if (upstream == null) {
                NO_BACKEND.incrementAndGet();
                client.close();
                return;
            }
            Socket up = upstream;
            Thread t = Thread.ofVirtual().start(() -> pipe(client, up));
            pipe(up, client);
            t.join();
        } catch (Exception ignored) {
            // a reset connection ends here
        } finally {
            close(client);
            close(upstream);
        }
    }

    private static Backend pick() {
        int n = BACKENDS.size();
        int start = Math.floorMod(NEXT.getAndIncrement(), n);
        for (int i = 0; i < n; i++) {
            Backend b = BACKENDS.get((start + i) % n);
            if (b.up) {
                return b;
            }
        }
        return null;
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // the other side went away
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private static void healthLoop() {
        while (true) {
            for (Backend b : BACKENDS) {
                mark(b, probe(b), false);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static boolean probe(Backend b) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(b.host, b.port), 1000);
            s.setSoTimeout(1500);
            s.getOutputStream().write(("GET /actuator/health HTTP/1.1\r\nHost: " + b.host
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String r = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return r.startsWith("HTTP/1.1 200") && r.contains("\"UP\"");
        } catch (IOException e) {
            return false;
        }
    }

    private static synchronized void mark(Backend b, boolean ok, boolean immediate) {
        boolean was = b.up;
        if (ok) {
            b.fails = 0;
            if (!b.up && ++b.passes >= 2) {
                b.up = true;
            }
        } else {
            b.passes = 0;
            if (b.up && (immediate || ++b.fails >= 2)) {
                b.up = false;
            }
        }
        if (was != b.up) {
            b.transitions.incrementAndGet();
            System.out.println(java.time.Instant.now() + " backend " + b.host + " is " + (b.up ? "UP" : "DOWN"));
        }
    }

    private static void serveStats(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            while (true) {
                try (Socket c = s.accept()) {
                    c.setSoTimeout(2000);
                    byte[] buf = new byte[1024];
                    int n = c.getInputStream().read(buf);
                    String req = n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII) : "";
                    String body = req.startsWith("GET /healthz") ? "ok" : statsJson();
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    c.getOutputStream().write(b);
                } catch (IOException ignored) {
                    // a client that left
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String statsJson() {
        StringBuilder sb = new StringBuilder("{\"accepted\":").append(ACCEPTED.get())
                .append(",\"noBackend\":").append(NO_BACKEND.get()).append(",\"backends\":[");
        for (int i = 0; i < BACKENDS.size(); i++) {
            Backend b = BACKENDS.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"host\":\"").append(b.host).append("\",\"up\":").append(b.up)
                    .append(",\"connections\":").append(b.connections.get())
                    .append(",\"connectFailures\":").append(b.connectFailures.get())
                    .append(",\"transitions\":").append(b.transitions.get()).append("}");
        }
        return sb.append("]}").toString();
    }
}
