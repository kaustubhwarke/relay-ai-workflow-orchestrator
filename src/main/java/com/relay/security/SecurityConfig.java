package com.relay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import com.relay.web.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Deny-by-default HTTP security: stateless bearer tokens with builder / operator / approver roles. The webhook
 * route is open to the world and guarded by its per-workflow secret instead.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String DEFAULT_DEMO_TOKEN = "relay-demo-token";

    @Bean
    public PrincipalRegistry principalRegistry(RelayProperties props, Environment env) {
        boolean prod = java.util.Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (DEFAULT_DEMO_TOKEN.equals(props.getSecurity().getApiToken())) {
            if (prod) {
                throw new IllegalStateException("RELAY_API_TOKEN must be set to a non-default value in the prod profile");
            }
            log.warn("Using the default demo API token. Set RELAY_API_TOKEN before exposing this service.");
        }
        return new PrincipalRegistry(props);
    }

    /** Authentication is bearer-token only; declare an empty user store so Spring does not generate a password. */
    @Bean
    public UserDetailsService noInteractiveUsers() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, PrincipalRegistry registry, ObjectMapper mapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(f -> f.deny()).contentTypeOptions(Customizer.withDefaults()))
                .addFilterBefore(new BearerTokenFilter(registry), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // public: webhook (secret-guarded), health probes, the console shell, API docs
                        .requestMatchers("/hooks/**", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/console/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // builders author and publish workflows
                        .requestMatchers(HttpMethod.POST, "/workflows/*/trigger").hasAnyRole(RelayPrincipal.BUILDER, RelayPrincipal.OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/workflows", "/workflows/validate", "/workflows/compile", "/workflows/compile/eval",
                                "/workflows/*/publish").hasRole(RelayPrincipal.BUILDER)
                        .requestMatchers(HttpMethod.PUT, "/workflows/*").hasRole(RelayPrincipal.BUILDER)
                        // operators cancel and replay runs
                        .requestMatchers(HttpMethod.POST, "/runs/**").hasRole(RelayPrincipal.OPERATOR)
                        // approvers decide
                        .requestMatchers(HttpMethod.POST, "/approvals/**").hasRole(RelayPrincipal.APPROVER)
                        .requestMatchers(HttpMethod.GET, "/workflows/**", "/runs/**", "/approvals/**", "/catalog", "/stats").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(mapper, res, HttpServletResponse.SC_UNAUTHORIZED,
                                "unauthorized", "Missing or invalid bearer token"))
                        .accessDeniedHandler((req, res, ex) -> writeError(mapper, res, HttpServletResponse.SC_FORBIDDEN,
                                "forbidden", "Your token does not have the role required for this action")));
        return http.build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse res, int status, String code, String message)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write(mapper.writeValueAsString(ApiExceptionHandler.ErrorBody.of(code, message, null)));
    }
}
