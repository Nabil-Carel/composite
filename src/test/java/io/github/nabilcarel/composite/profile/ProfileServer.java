package io.github.nabilcarel.composite.profile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The profiled application, run in its own JVM so client work cannot affect its heap or GC.
 *
 * <p>The parent process controls it over standard input with {@code START file}, {@code STOP}
 * and {@code EXIT}. Replies use the {@code PROFILE} prefix on standard output.
 */
public final class ProfileServer {

    private ProfileServer() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream control = System.out;

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ProfileApplication.class)
                .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=ERROR",
                    "spring.threads.virtual.enabled=" + Boolean.getBoolean("composite.profile.virtualThreads"),
                    "composite.loopback.client=" + System.getProperty("composite.profile.dispatcher", "jdk"))
                .run();
             BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            control.println("PROFILE PORT " + context.getEnvironment().getProperty("local.server.port"));
            control.flush();

            Recording recording = null;
            Path file = null;
            Map<Long, Long> before = Map.of();

            for (String command = commands.readLine(); command != null; command = commands.readLine()) {
                if (command.startsWith("START ")) {
                    file = Path.of(command.substring(6));
                    recording = new Recording(Configuration.getConfiguration("profile"));
                    recording.enable("io.github.nabilcarel.composite.Batch");
                    recording.enable("io.github.nabilcarel.composite.SubRequest");
                    recording.start();
                    before = serverAllocations();
                    control.println("PROFILE STARTED");
                } else if (command.equals("STOP")) {
                    long allocated = allocatedSince(before);
                    Thread.sleep(200);
                    recording.stop();
                    recording.dump(file);
                    recording.close();
                    control.println("PROFILE ALLOCATED " + allocated);
                } else if (command.equals("EXIT")) {
                    return;
                }
                control.flush();
            }
        }
    }

    private static boolean isServerThread(String name) {
        return name != null && (name.startsWith("http-nio-") || name.startsWith("reactor-http-")
            || name.startsWith("HttpClient-") || name.startsWith("composite-http-"));
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
}
