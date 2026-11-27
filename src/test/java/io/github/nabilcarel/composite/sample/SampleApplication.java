package io.github.nabilcarel.composite.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small secured order API used to exercise the library as a host application would.
 *
 * <p>Select authentication with {@code sample.security.mode}: {@code jwt} (default),
 * {@code token}, {@code basic} or {@code session}.
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
