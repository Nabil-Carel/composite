package io.github.nabilcarel.composite.profile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Drives batches of 1, 5 and 25 sub-requests against a separate server JVM.
 *
 * <p>Run with {@code ./gradlew jvmProfile}. Client and server still share the machine, so
 * compare runs on the same computer rather than treating results as capacity numbers.
 */
public final class CompositeProfiler {

    private static final int[] BATCH_SIZES = {1, 5, 25};
    private static final String SUB_REQUEST_EVENT = "io.github.nabilcarel.composite.SubRequest";
    private static final String BATCH_EVENT = "io.github.nabilcarel.composite.Batch";
    private static final String COMPOSITE_PACKAGE = "io.github.nabilcarel.composite.";
    private static final Map<String, List<String>> SUBSYSTEMS = subsystems();

    private CompositeProfiler() {
    }

    private record Load(long batches, long errors, long[] latenciesNanos, Duration elapsed) {
    }

    private static final boolean VIRTUAL_THREADS = Boolean.getBoolean("composite.profile.virtualThreads");
    private static final String DISPATCHER = System.getProperty("composite.profile.dispatcher", "jdk");
    private static final long HANDLER_DELAY_MILLIS = Long.getLong("composite.profile.handlerDelayMillis", 0);
    private static final Duration CLIENT_TIMEOUT =
        Duration.ofSeconds(Long.getLong("composite.profile.clientTimeoutSeconds", 30));
    private static final String MAX_CONNECTIONS = System.getProperty("composite.profile.maxConnections", "100");

    private record Result(int size, Load load, double allocatedKbPerBatch, long gcCount, double gcPauseMillis,
                          double subRequestP99Millis, long batchEvents, long subRequestEvents,
                          long failedSubRequests, Map<Integer, Long> failedStatuses, long samples,
                          double jacksonPercent, Map<String, Double> cpuBySubsystem,
                          Map<String, Double> allocationBySubsystem, double compositeTriggeredPercent,
                          Map<String, Double> compositeAllocationSites) {
    }

    /** Controls the server JVM. */
    private static final class ServerProcess implements AutoCloseable {

        private final Process process;
        private final BufferedWriter commands;
        private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        private final java.util.concurrent.atomic.AtomicBoolean dumped = new java.util.concurrent.atomic.AtomicBoolean();
        private final int port;

        private ServerProcess(Path serverLog) throws IOException {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            process = new ProcessBuilder(java, "-Xms512m", "-Xmx512m", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints",
                "-Dcomposite.profile.virtualThreads=" + VIRTUAL_THREADS,
                "-Dcomposite.profile.dispatcher=" + DISPATCHER,
                "-Dcomposite.profile.maxConnections=" + MAX_CONNECTIONS,
                "-cp", System.getProperty("java.class.path"), ProfileServer.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
            commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            // A pipe that is not read fills up and blocks every server thread that logs.
            BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread drainer = new Thread(() -> drain(output, serverLog), "profile-server-output");
            drainer.setDaemon(true);
            drainer.start();
            port = Integer.parseInt(reply("PORT "));
        }

        private void drain(BufferedReader output, Path serverLog) {
            try (BufferedWriter log = Files.newBufferedWriter(serverLog, StandardCharsets.UTF_8)) {
                for (String line = output.readLine(); line != null; line = output.readLine()) {
                    if (line.startsWith("PROFILE ")) {
                        replies.add(line);
                    } else {
                        log.write(line);
                        log.newLine();
                    }
                }
            } catch (IOException ignored) {
                // The server exited.
            } finally {
                replies.add("PROFILE EXITED");
            }
        }

        private String reply(String prefix) throws IOException {
            String expected = "PROFILE " + prefix;
            try {
                for (String line = replies.poll(120, TimeUnit.SECONDS); line != null;
                     line = replies.poll(120, TimeUnit.SECONDS)) {
                    if (line.startsWith(expected)) {
                        return line.substring(expected.length()).trim();
                    }
                    if (line.equals("PROFILE EXITED")) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Profile server did not reply with " + prefix);
        }

        private void send(String command) throws IOException {
            commands.write(command);
            commands.newLine();
            commands.flush();
        }

        private void startRecording(Path file) throws IOException {
            send("START " + file.toAbsolutePath());
            reply("STARTED");
        }

        /** Writes every server thread, virtual threads included, the first time a request times out. */
        private void dumpThreadsOnce(Path file) {
            if (!dumped.compareAndSet(false, true)) {
                return;
            }
            String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
            try {
                Files.deleteIfExists(file);
                Process dump = new ProcessBuilder(jcmd, String.valueOf(process.pid()), "Thread.dump_to_file",
                    "-format=text", file.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
                dump.waitFor(30, TimeUnit.SECONDS);
                System.err.println("A request timed out. Server threads written to " + file);
            } catch (IOException e) {
                System.err.println("A request timed out, and the server threads could not be dumped: " + e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private long stopRecording() throws IOException {
            send("STOP");
            return Long.parseLong(reply("ALLOCATED "));
        }

        @Override
        public void close() throws Exception {
            try {
                send("EXIT");
            } catch (IOException ignored) {
                // The process already exited.
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int concurrency = Integer.getInteger("composite.profile.concurrency", 16);
        Duration warmup = Duration.ofSeconds(Integer.getInteger("composite.profile.warmupSeconds", 10));
        Duration measure = Duration.ofSeconds(Integer.getInteger("composite.profile.measureSeconds", 15));
        Path output = Path.of(System.getProperty("composite.profile.output", "build/reports/jvm-profile"));
        Files.createDirectories(output);

        List<Result> results = new ArrayList<>();
        try (ServerProcess server = new ServerProcess(output.resolve("server.log"))) {
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            Runnable onTimeout = () -> server.dumpThreadsOnce(output.resolve("server-threads.txt"));

            for (int size : BATCH_SIZES) {
                HttpRequest request = request(server.port, size);
                drive(client, request, concurrency, warmup, onTimeout);

                Path file = output.resolve("batch-" + size + ".jfr");
                server.startRecording(file);
                Load load = drive(client, request, concurrency, measure, onTimeout);
                long allocated = server.stopRecording();
                results.add(analyze(size, load, allocated, file));
            }
        }

        String report = report(results, concurrency, warmup, measure);
        Files.writeString(output.resolve("profile.md"), report);
        System.out.println(report);
    }

    private static HttpRequest request(int port, int size) {
        String delay = HANDLER_DELAY_MILLIS > 0 ? "?delayMs=" + HANDLER_DELAY_MILLIS : "";
        String dependents = IntStream.range(1, size)
            .mapToObj(i -> ",{\"referenceId\":\"item" + i + "\",\"method\":\"GET\",\"url\":\"/profile/items/${root.id}"
                + delay + "\"}")
            .collect(Collectors.joining());
        String body = "{\"subRequests\":[{\"referenceId\":\"root\",\"method\":\"GET\",\"url\":\"/profile/items/1"
            + delay + "\"}" + dependents + "]}";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/composite/execute"))
            .timeout(CLIENT_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private static Load drive(HttpClient client, HttpRequest request, int concurrency, Duration duration,
                              Runnable onTimeout) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "profile-client");
            thread.setDaemon(true);
            return thread;
        });
        long started = System.nanoTime();
        long deadline = started + duration.toNanos();
        List<Future<long[]>> workers = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            workers.add(executor.submit(() -> {
                long[] latencies = new long[1024];
                int count = 0;
                long errors = 0;
                while (System.nanoTime() < deadline) {
                    long begin = System.nanoTime();
                    HttpResponse<String> response;
                    try {
                        response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    } catch (HttpTimeoutException e) {
                        // Counted as an error, so the CI job still fails, and the server's threads
                        // are captured while the batch is stuck.
                        errors++;
                        onTimeout.run();
                        continue;
                    }
                    if (count == latencies.length) {
                        latencies = Arrays.copyOf(latencies, count * 2);
                    }
                    latencies[count++] = System.nanoTime() - begin;
                    if (response.statusCode() != 200 || !response.body().contains("\"hasErrors\":false")) {
                        errors++;
                    }
                }
                long[] result = Arrays.copyOf(latencies, count + 1);
                result[count] = errors;
                return result;
            }));
        }

        List<long[]> parts = new ArrayList<>();
        long errors = 0;
        for (Future<long[]> worker : workers) {
            long[] part = worker.get();
            errors += part[part.length - 1];
            parts.add(Arrays.copyOf(part, part.length - 1));
        }
        executor.shutdown();

        long[] latencies = parts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        return new Load(latencies.length, errors, latencies, Duration.ofNanos(System.nanoTime() - started));
    }

    private static boolean isServerThread(RecordedThread thread) {
        String name = thread == null ? null : thread.getJavaName();
        return name != null && (name.startsWith("http-nio-") || name.startsWith("reactor-http-")
            || name.startsWith("HttpClient-") || name.startsWith("composite-http-") || name.startsWith("tomcat-handler-"));
    }

    private static Result analyze(int size, Load load, long allocated, Path file) throws IOException {
        long gcCount = 0;
        double gcPauseMillis = 0;
        long batchEvents = 0;
        long samples = 0;
        long jacksonSamples = 0;
        long allocationWeight = 0;
        long compositeWeight = 0;
        List<Double> subRequestMillis = new ArrayList<>();
        Map<String, Long> cpu = emptySubsystemCounts();
        Map<String, Long> allocation = emptySubsystemCounts();
        Map<String, Long> compositeSites = new HashMap<>();
        Map<Integer, Long> failedStatuses = new java.util.TreeMap<>();

        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            switch (event.getEventType().getName()) {
                case "jdk.GarbageCollection" -> {
                    gcCount++;
                    gcPauseMillis += event.getDuration("sumOfPauses").toNanos() / 1_000_000.0;
                }
                case BATCH_EVENT -> batchEvents++;
                case SUB_REQUEST_EVENT -> {
                    subRequestMillis.add(event.getDuration().toNanos() / 1_000_000.0);
                    int status = event.getInt("status");
                    if (status < 200 || status >= 300) {
                        failedStatuses.merge(status, 1L, Long::sum);
                    }
                }
                case "jdk.ExecutionSample" -> {
                    if (isServerThread(event.getThread("sampledThread")) && event.getStackTrace() != null) {
                        samples++;
                        List<RecordedFrame> frames = event.getStackTrace().getFrames();
                        if (frames.stream().anyMatch(frame -> typeName(frame).startsWith("com.fasterxml.jackson."))) {
                            jacksonSamples++;
                        }
                        cpu.merge(subsystem(frames), 1L, Long::sum);
                    }
                }
                case "jdk.ObjectAllocationSample" -> {
                    if (isServerThread(event.getThread("eventThread")) && event.getStackTrace() != null) {
                        long weight = event.getLong("weight");
                        List<RecordedFrame> frames = event.getStackTrace().getFrames();
                        allocationWeight += weight;
                        allocation.merge(subsystem(frames), weight, Long::sum);
                        String site = nearestCompositeFrame(frames);
                        if (site != null) {
                            compositeWeight += weight;
                            compositeSites.merge(site, weight, Long::sum);
                        }
                    }
                }
                default -> {
                }
            }
        }

        subRequestMillis.sort(Double::compare);
        double subRequestP99 = subRequestMillis.isEmpty() ? 0
            : subRequestMillis.get(Math.min(subRequestMillis.size() - 1, (int) (subRequestMillis.size() * 0.99)));

        long totalWeight = allocationWeight;
        Map<String, Double> topSites = new LinkedHashMap<>();
        compositeSites.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
            .limit(12)
            .forEach(site -> topSites.put(site.getKey(), percent(site.getValue(), totalWeight)));

        // Thread counters cannot see virtual threads, so JFR's sampled estimate is used for them.
        double allocatedBytes = VIRTUAL_THREADS ? allocationWeight : allocated;
        return new Result(size, load, allocatedBytes / 1024.0 / Math.max(1, load.batches()), gcCount, gcPauseMillis,
            subRequestP99, batchEvents, subRequestMillis.size(),
            failedStatuses.values().stream().mapToLong(Long::longValue).sum(), failedStatuses, samples, percent(jacksonSamples, samples),
            percentages(cpu, samples), percentages(allocation, allocationWeight),
            percent(compositeWeight, allocationWeight), topSites);
    }

    private static Map<String, Long> emptySubsystemCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        SUBSYSTEMS.keySet().forEach(name -> counts.put(name, 0L));
        counts.put("JDK and other", 0L);
        return counts;
    }

    private static Map<String, Double> percentages(Map<String, Long> counts, long total) {
        Map<String, Double> percentages = new LinkedHashMap<>();
        counts.forEach((name, count) -> percentages.put(name, percent(count, total)));
        return percentages;
    }

    private static double percent(long value, long total) {
        return total == 0 ? 0 : 100.0 * value / total;
    }

    private static String typeName(RecordedFrame frame) {
        return frame.getMethod() == null ? "" : frame.getMethod().getType().getName();
    }

    private static String nearestCompositeFrame(List<RecordedFrame> frames) {
        for (RecordedFrame frame : frames) {
            String type = typeName(frame);
            if (type.startsWith(COMPOSITE_PACKAGE) && !type.startsWith(COMPOSITE_PACKAGE + "profile.")) {
                return type.substring(COMPOSITE_PACKAGE.length()) + "." + frame.getMethod().getName();
            }
        }
        return null;
    }

    /** Attributes a sample to the nearest known subsystem, starting from the executing frame. */
    private static String subsystem(List<RecordedFrame> frames) {
        for (RecordedFrame frame : frames) {
            String type = typeName(frame);
            for (Map.Entry<String, List<String>> subsystem : SUBSYSTEMS.entrySet()) {
                if (subsystem.getValue().stream().anyMatch(type::startsWith)) {
                    return subsystem.getKey();
                }
            }
        }
        return "JDK and other";
    }

    private static Map<String, List<String>> subsystems() {
        Map<String, List<String>> subsystems = new LinkedHashMap<>();
        subsystems.put("Jackson", List.of("com.fasterxml.jackson."));
        subsystems.put("Composite", List.of(COMPOSITE_PACKAGE));
        subsystems.put("Netty and Reactor", List.of("io.netty.", "reactor."));
        subsystems.put("Tomcat", List.of("org.apache.catalina.", "org.apache.coyote.", "org.apache.tomcat."));
        subsystems.put("Spring", List.of("org.springframework."));
        return subsystems;
    }

    private static double percentileMillis(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = Math.min(sortedNanos.length - 1, (int) Math.ceil(percentile * sortedNanos.length) - 1);
        return sortedNanos[Math.max(0, index)] / 1_000_000.0;
    }

    private static String report(List<Result> results, int concurrency, Duration warmup, Duration measure) {
        StringBuilder report = new StringBuilder();
        report.append("# Composite JVM Profile\n\n");
        report.append(String.format(Locale.ROOT,
            "Java %s, %d CPUs, separate 512 MB server JVM, %d clients, %ds warmup, %ds measured per size.%n%n"
                + "Tomcat threads: %s. Dispatcher: %s. Handler delay: %d ms. Loopback connections: %s.%n%n",
            System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), concurrency,
            warmup.toSeconds(), measure.toSeconds(), VIRTUAL_THREADS ? "virtual" : "platform", DISPATCHER,
            HANDLER_DELAY_MILLIS, MAX_CONNECTIONS));
        if (VIRTUAL_THREADS) {
            report.append("Allocation is JFR's sampled estimate, because thread counters cannot see virtual threads.\n\n");
        }
        report.append("Client and server share this machine. Compare runs on the same computer.\n\n");
        report.append("| Sub-requests | Batches/s | p50 ms | p95 ms | p99 ms | Errors | Failed sub-requests | KB allocated/batch | GCs | GC pause ms | Sub-request p99 ms |\n");
        report.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Result result : results) {
            Load load = result.load();
            report.append(String.format(Locale.ROOT, "| %d | %.0f | %.2f | %.2f | %.2f | %d | %d | %.1f | %d | %.1f | %.2f |%n",
                result.size(), load.batches() / (load.elapsed().toNanos() / 1e9),
                percentileMillis(load.latenciesNanos(), 0.50), percentileMillis(load.latenciesNanos(), 0.95),
                percentileMillis(load.latenciesNanos(), 0.99), load.errors(), result.failedSubRequests(),
                result.allocatedKbPerBatch(),
                result.gcCount(), result.gcPauseMillis(), result.subRequestP99Millis()));
        }

        report.append("\n## Server CPU by nearest subsystem\n\n");
        appendSubsystems(report, results, true);
        report.append("\n## Sampled allocation by nearest subsystem\n\n");
        appendSubsystems(report, results, false);

        report.append("\n## Allocation triggered from Composite frames\n\n");
        for (Result result : results) {
            report.append(String.format(Locale.ROOT, "### %d sub-requests: %.1f%% of sampled allocation%n%n",
                result.size(), result.compositeTriggeredPercent()));
            result.compositeAllocationSites().forEach((site, share) ->
                report.append(String.format(Locale.ROOT, "- %.1f%% `%s`%n", share, site)));
            report.append("\n");
        }

        report.append("## JFR events\n\n");
        for (Result result : results) {
            report.append(String.format(Locale.ROOT, "- Size %d: %d batch events, %d sub-request events, failed by status %s%n",
                result.size(), result.batchEvents(), result.subRequestEvents(), result.failedStatuses()));
        }
        return report.toString();
    }

    private static void appendSubsystems(StringBuilder report, List<Result> results, boolean cpu) {
        Map<String, Double> first = cpu ? results.get(0).cpuBySubsystem() : results.get(0).allocationBySubsystem();
        report.append("| Sub-requests | ").append(String.join(" | ", first.keySet())).append(" |\n|---:|");
        first.keySet().forEach(name -> report.append("---:|"));
        report.append("\n");
        for (Result result : results) {
            Map<String, Double> values = cpu ? result.cpuBySubsystem() : result.allocationBySubsystem();
            report.append("| ").append(result.size()).append(" |");
            values.values().forEach(value -> report.append(String.format(Locale.ROOT, " %.1f%% |", value)));
            report.append("\n");
        }
    }
}
