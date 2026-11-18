package nrw.andresen.stbl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Login with the form of Spring Security for the pages, user and password from spring.security.user.*.
 * <ul>
 * <li>Only a page opened in the browser leads to the login form. Other requests without login, e.g. the update of
 * a page every 20 s after the session ran out, get 401 without asking for Basic authentication: the browser showed
 * its own password dialog besides the form.</li>
 * <li>After the login only a page is opened again, not an icon or a script the browser asked for meanwhile, which
 * ended on the error page.</li>
 * <li>Basic authentication sent with the request, e.g. curl -u, still works.</li>
 * </ul>
 */
@Configuration
public class Sicherheit {

    static final String START = "/status";

    @Bean
    public SecurityFilterChain sicherheitsFilter(HttpSecurity http) throws Exception {
        HttpSessionRequestCache seiten = new HttpSessionRequestCache();
        seiten.setRequestMatcher(Sicherheit::seite);
        HttpStatusEntryPoint ohne = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        http.authorizeHttpRequests(a -> a.requestMatchers("/error", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .requestCache(c -> c.requestCache(seiten))
                .formLogin(f -> f.defaultSuccessUrl(START))
                // Pages opened in the browser go to the form, everything else gets the entry point of Basic
                // authentication, here without the header that opens the password dialog of the browser. An own
                // entry point for all requests would switch off the generated login form.
                .httpBasic(b -> b.authenticationEntryPoint(ohne))
                .csrf(Customizer.withDefaults());
        return http.build();
    }

    /**
     * A page opened in the browser: GET, HTML wanted, not sent by a script and not a static file or the error page
     */
    static boolean seite(HttpServletRequest request) {
        String pfad = request.getRequestURI().substring(request.getContextPath().length());
        String accept = request.getHeader("Accept");
        return "GET".equals(request.getMethod()) && accept != null && accept.contains("text/html")
                && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                && !"cors".equals(request.getHeader("Sec-Fetch-Mode"))
                && !"same-origin".equals(request.getHeader("Sec-Fetch-Mode"))
                && !pfad.startsWith("/js/") && !pfad.startsWith("/error") && !pfad.contains(".");
    }
}
