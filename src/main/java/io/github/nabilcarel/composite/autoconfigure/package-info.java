/**
 * Auto-configuration classes for the Composite library.
 *
 * <p>The entry point is
 * {@link io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration}, which
 * is registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and activated automatically by Spring Boot's auto-configuration mechanism.
 *
 * <p>Also contains the optional Actuator
 * {@link io.github.nabilcarel.composite.autoconfigure.CompositeHealthIndicator health indicator},
 * which is registered only when {@code spring-boot-actuator} is on the classpath.
 *
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeHealthIndicator
 */
package io.github.nabilcarel.composite.autoconfigure;
