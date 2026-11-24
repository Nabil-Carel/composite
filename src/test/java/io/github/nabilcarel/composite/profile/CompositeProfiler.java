package io.github.nabilcarel.composite.profile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Drives batches of 1, 5 and 25 sub-requests and reports what they cost on the server.
 *
 * <p>Run with {@code ./gradlew jvmProfile}. The load generator shares the JVM and machine, so
 * compare runs on the same computer rather than treating results as capacity numbers.
 */
public final class CompositeProfiler {

    private static final int[] BATCH_SIZES = {1, 5, 25};
    private static final String SUB_REQUEST_EVENT = "io.github.nabilcarel.composite.SubRequest";
    private static final String BATCH_EVENT = "io.github.nabilcarel.composite.Batch";
    private static final Map<String, List<String>> SUBSYSTEMS = subsystems();

    private CompositeProfiler() {
    }

    private record Load(long batches, long errors, long[] latenciesNanos, Duration elapsed) {
    }

    private record Result(int size, Load load, double allocatedKbPerBatch, long gcCount, double gcPauseMillis,
                          double subRequestP99Millis, long batchEvents, long subRequestEvents, long samples,
                          double jacksonPercent, Map<String, Double> cpuBySubsystem) {
    }

    public static void main(String[] args) throws Exception {
        int concurrency = Integer.getInteger("composite.profile.concurrency", 16);
        Duration warmup = Duration.ofSeconds(Integer.getInteger("composite.profile.warmupSeconds", 10));
        Duration measure = Duration.ofSeconds(Integer.getInteger("composite.profile.measureSeconds", 15));
        Path output = Path.of(System.getProperty("composite.profile.output", "build/reports/jvm-profile"));
        Files.createDirectories(output);

        List<Result> results = new ArrayList<>();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ProfileApplication.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=ERROR")
                .run()) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

            for (int size : BATCH_SIZES) {
                HttpRequest request = request(port, size);
                drive(client, request, concurrency, warmup);
                results.add(measure(client, request, size, concurrency, measure, output));
            }
        }

        String report = report(results, concurrency, warmup, measure);
        Files.writeString(output.resolve("profile.md"), report);
        System.out.println(report);
    }

    private static HttpRequest request(int port, int size) {
        String dependents = IntStream.range(1, size)
            .mapToObj(i -> ",{\"referenceId\":\"item" + i + "\",\"method\":\"GET\",\"url\":\"/profile/items/${root.id}\"}")
            .collect(Collectors.joining());
        String body = "{\"subRequests\":[{\"referenceId\":\"root\",\"method\":\"GET\",\"url\":\"/profile/items/1\"}"
            + dependents + "]}";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/composite/execute"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private static Result measure(HttpClient client, HttpRequest request, int size, int concurrency,
                                  Duration duration, Path output) throws Exception {
        Path file = output.resolve("batch-" + size + ".jfr");
        Load load;
        long allocated;

        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.enable(BATCH_EVENT);
            recording.enable(SUB_REQUEST_EVENT);
            recording.start();
            Map<Long, Long> before = serverAllocations();
            load = drive(client, request, concurrency, duration);
            allocated = allocatedSince(before);
            Thread.sleep(200);
            recording.stop();
            recording.dump(file);
        }

        return analyze(size, load, allocated, file);
    }

    private static Load drive(HttpClient client, HttpRequest request, int concurrency, Duration duration)
            throws Exception {
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
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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

    private static boolean isServerThread(String name) {
        return name != null && (name.startsWith("http-nio-") || name.startsWith("reactor-http-"));
    }

    private static Map<Long, Long> serverAllocations() {
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Map<Long, Long> allocations = new HashMap<>();
        for (var info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info != null && isServerThread(info.getThreadName())) {
                allocations.put(info.getThreadId(), threads.getThreadAllocatedBytes(info.getThreadId()));
            }
        }
        return allocations;
    }

    private static long allocatedSince(Map<Long, Long> before) {
        long total = 0;
        for (Map.Entry<Long, Long> current : serverAllocations().entrySet()) {
            total += Math.max(0, current.getValue() - before.getOrDefault(current.getKey(), 0L));
        }
        return total;
    }

    private static Result analyze(int size, Load load, long allocated, Path file) throws IOException {
        long gcCount = 0;
        double gcPauseMillis = 0;
        long batchEvents = 0;
        long samples = 0;
        long jacksonSamples = 0;
        List<Double> subRequestMillis = new ArrayList<>();
        Map<String, Long> cpu = new LinkedHashMap<>();
        SUBSYSTEMS.keySet().forEach(name -> cpu.put(name, 0L));
        cpu.put("JDK and other", 0L);

        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            String type = event.getEventType().getName();
            switch (type) {
                case "jdk.GarbageCollection" -> {
                    gcCount++;
                    gcPauseMillis += event.getDuration("sumOfPauses").toNanos() / 1_000_000.0;
                }
                case BATCH_EVENT -> batchEvents++;
                case SUB_REQUEST_EVENT -> subRequestMillis.add(event.getDuration().toNanos() / 1_000_000.0);
                case "jdk.ExecutionSample" -> {
                    RecordedThread thread = event.getThread("sampledThread");
                    if (thread != null && isServerThread(thread.getJavaName()) && event.getStackTrace() != null) {
                        samples++;
                        List<RecordedFrame> frames = event.getStackTrace().getFrames();
                        if (frames.stream().anyMatch(frame -> typeName(frame).startsWith("com.fasterxml.jackson."))) {
                            jacksonSamples++;
                        }
                        cpu.merge(subsystem(frames), 1L, Long::sum);
                    }
                }
                default -> {
                }
            }
        }

        subRequestMillis.sort(Double::compare);
        double subRequestP99 = subRequestMillis.isEmpty() ? 0
            : subRequestMillis.get(Math.min(subRequestMillis.size() - 1, (int) (subRequestMillis.size() * 0.99)));
        long sampleCount = samples;
        Map<String, Double> cpuPercent = new LinkedHashMap<>();
        cpu.forEach((name, count) -> cpuPercent.put(name, sampleCount == 0 ? 0 : 100.0 * count / sampleCount));

        return new Result(size, load, allocated / 1024.0 / Math.max(1, load.batches()), gcCount, gcPauseMillis,
            subRequestP99, batchEvents, subRequestMillis.size(), samples,
            samples == 0 ? 0 : 100.0 * jacksonSamples / samples, cpuPercent);
    }

    private static String typeName(RecordedFrame frame) {
        return frame.getMethod() == null ? "" : frame.getMethod().getType().getName();
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
        subsystems.put("Composite", List.of("io.github.nabilcarel.composite."));
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
        report.append(String.format(Locale.ROOT, "Java %s, %d CPUs, %d MB heap, %d clients, %ds warmup, %ds measured per size.%n%n",
            System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().maxMemory() / (1024 * 1024), concurrency, warmup.toSeconds(), measure.toSeconds()));
        report.append("The load generator runs in the same JVM. Compare runs on the same machine.\n\n");
        report.append("| Sub-requests | Batches/s | p50 ms | p95 ms | p99 ms | Errors | KB allocated/batch | GCs | GC pause ms | Sub-request p99 ms |\n");
        report.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Result result : results) {
            Load load = result.load();
            report.append(String.format(Locale.ROOT, "| %d | %.0f | %.2f | %.2f | %.2f | %d | %.1f | %d | %.1f | %.2f |%n",
                result.size(), load.batches() / (load.elapsed().toNanos() / 1e9),
                percentileMillis(load.latenciesNanos(), 0.50), percentileMillis(load.latenciesNanos(), 0.95),
                percentileMillis(load.latenciesNanos(), 0.99), load.errors(), result.allocatedKbPerBatch(),
                result.gcCount(), result.gcPauseMillis(), result.subRequestP99Millis()));
        }

        report.append("\n## Server CPU by nearest subsystem\n\n");
        report.append("| Sub-requests | Samples | Any Jackson frame | ");
        report.append(String.join(" | ", results.get(0).cpuBySubsystem().keySet())).append(" |\n");
        report.append("|---:|---:|---:|");
        results.get(0).cpuBySubsystem().keySet().forEach(name -> report.append("---:|"));
        report.append("\n");
        for (Result result : results) {
            report.append(String.format(Locale.ROOT, "| %d | %d | %.1f%% |", result.size(), result.samples(),
                result.jacksonPercent()));
            result.cpuBySubsystem().values().forEach(percent ->
                report.append(String.format(Locale.ROOT, " %.1f%% |", percent)));
            report.append("\n");
        }

        report.append("\n## JFR events\n\n");
        for (Result result : results) {
            report.append(String.format(Locale.ROOT, "- Size %d: %d batch events, %d sub-request events%n",
                result.size(), result.batchEvents(), result.subRequestEvents()));
        }
        return report.toString();
    }
}
