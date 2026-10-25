package com.chitfund.portal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two kinds of caller, two rules:
 * <ul>
 *   <li>{@code /api/v1/portal/admin/**}: the admin credential (HTTP Basic). A member session cookie means nothing here.</li>
 *   <li>everything else under {@code /api/v1/portal/**}: open to the framework; each call checks the member session cookie itself
 *       ({@code PortalController}), and every state-changing call is guarded against cross-site requests ({@code OriginGuard}).
 *       CSRF protection of the framework is off because the API is cookie-authenticated through that explicit guard, not through forms.</li>
 * </ul>
 * The admin credential is separate from the chitfund-service credential; this service sends the latter to chitfund-service only.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Admin credential required\"}");
        };
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/portal/admin/**").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized));
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(@Value("${chit.portal.admin.username}") String username,
                                                 @Value("${chit.portal.admin.password}") String password) {
        UserDetails admin = User.withUsername(username).password("{noop}" + password).roles("PORTAL_ADMIN").build();
        return new InMemoryUserDetailsManager(admin);
    }
}
