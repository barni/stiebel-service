package nrw.andresen.stbl;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
 * <li>The login form is not checked against forms from other sites: Safari on the iPhone showed a stored login page
 * whose token belonged to a session that was gone after a restart or 30 minutes, the login ended with 403 on the
 * error page. With a single user a forged login gains nothing; all other forms keep the check.</li>
 * <li>The login is remembered for 30 days in a cookie signed with the password, so a restart of the service does not
 * ask again. A new password makes the cookie invalid.</li>
 * </ul>
 */
@Configuration
public class Sicherheit {

    static final String START = "/status";
    static final int ANGEMELDET_BLEIBEN_S = 30 * 24 * 3600;

    @Value("${spring.security.user.name:user}")
    private String benutzer;
    @Value("${spring.security.user.password:}")
    private String passwort;

    /**
     * The user from spring.security.user.*, a new object for each request: the cookie of the remembered login is
     * signed with the password, and after a login Spring erases the password in the object it got; with one stored
     * object the cookie was signed without it and was invalid after every restart
     */
    @Bean
    public org.springframework.security.core.userdetails.UserDetailsService benutzerdienst() {
        return name -> {
            if (!benutzer.equals(name)) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException(name);
            }
            return org.springframework.security.core.userdetails.User.withUsername(benutzer)
                    .password("{noop}" + passwort).roles("USER").build();
        };
    }

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
                .rememberMe(r -> r.key(schluessel(benutzer, passwort)).alwaysRemember(true)
                        .tokenValiditySeconds(ANGEMELDET_BLEIBEN_S))
                .csrf(c -> c.ignoringRequestMatchers("/login"));
        return http.build();
    }

    /**
     * Key of the remembered login, the same after a restart as long as user and password stay
     */
    static String schluessel(String benutzer, String passwort) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("stbl-angemeldet:" + benutzer + ":" + passwort).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
