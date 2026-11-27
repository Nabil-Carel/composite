package io.github.nabilcarel.composite.sample;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/** Host-application security for each supported authentication style. */
@Configuration
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
class SampleSecurityConfiguration {

    static final String ALLOWED_ORIGIN = "https://app.example.com";
    static final String PASSWORD = "secret";

    private static final Map<String, List<String>> OPAQUE_TOKENS = Map.of(
        "alice-token", List.of("alice", "ROLE_USER"),
        "bob-token", List.of("bob", "ROLE_USER"),
        "root-token", List.of("root", "ROLE_USER", "ROLE_ADMIN"));

    @Value("${sample.security.public-composite:false}")
    private boolean publicComposite;

    private HttpSecurity commonRules(HttpSecurity http) throws Exception {
        return http
            .cors(withDefaults())
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                auth.requestMatchers("/api/public/**").permitAll();
                if (publicComposite) {
                    auth.requestMatchers("/api/composite/**").permitAll();
                }
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                auth.anyRequest().authenticated();
            });
    }

    @Bean
    @ConditionalOnProperty(name = "sample.security.mode", havingValue = "jwt", matchIfMissing = true)
    SecurityFilterChain jwtChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SampleSecurityConfiguration::jwtAuthorities);

        return commonRules(http)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "sample.security.mode", havingValue = "jwt", matchIfMissing = true)
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(SampleTokens.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO)));
        return decoder;
    }

    private static Collection<GrantedAuthority> jwtAuthorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            for (String value : scope.split(" ")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + value));
            }
        }
        return authorities;
    }

    @Bean
    @ConditionalOnProperty(name = "sample.security.mode", havingValue = "token")
    SecurityFilterChain tokenChain(HttpSecurity http) throws Exception {
        return commonRules(http)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new OpaqueTokenFilter(), UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "sample.security.mode", havingValue = "basic")
    SecurityFilterChain basicChain(HttpSecurity http) throws Exception {
        return commonRules(http)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "sample.security.mode", havingValue = "session")
    SecurityFilterChain sessionChain(HttpSecurity http) throws Exception {
        return commonRules(http)
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/login"))
            .formLogin(form -> form.successHandler((request, response, authentication) ->
                response.setStatus(HttpStatus.NO_CONTENT.value())))
            .logout(logout -> logout.logoutSuccessHandler(
                new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
            User.withUsername("alice").password("{noop}" + PASSWORD).roles("USER").build(),
            User.withUsername("bob").password("{noop}" + PASSWORD).roles("USER").build(),
            User.withUsername("root").password("{noop}" + PASSWORD).roles("USER", "ADMIN").build());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        configuration.setAllowedMethods(List.of("GET", "POST"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** The common hand-written filter, which authenticates only the initial dispatch. */
    private static final class OpaqueTokenFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                List<String> identity = OPAQUE_TOKENS.get(header.substring(7));
                if (identity != null) {
                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(identity.get(0), null,
                            AuthorityUtils.createAuthorityList(identity.subList(1, identity.size()))));
                }
            }
            chain.doFilter(request, response);
        }
    }
}
