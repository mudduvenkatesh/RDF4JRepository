package com.example.graphdb.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Spring configuration that wires an RDF4J {@link Repository} pointing at
 * an Ontotext GraphDB HTTP endpoint.
 *
 * <p>The {@link Repository} bean is thread-safe and should be treated as a
 * long-lived singleton; individual connections are obtained per-operation via
 * {@code repository.getConnection()}.
 *
 * <h3>Spring Boot 4 / Spring Framework 7 notes</h3>
 * <ul>
 *   <li>Jakarta EE 11 (Servlet 6.1) is now the baseline — no {@code javax.*} imports.</li>
 *   <li>JSpecify {@code @NonNull} / {@code @Nullable} replace
 *       {@code org.springframework.lang} equivalents.</li>
 *   <li>Undertow has been removed from Boot 4; Tomcat 11 is the default container.</li>
 *   <li>{@code destroyMethod = "shutDown"} ensures the RDF4J connection pool
 *       is released cleanly on context close (unchanged from Boot 3).</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GraphDbConfig {

    private final @NonNull GraphDbProperties props;

    /**
     * Builds and initialises an RDF4J {@link HTTPRepository} connecting to
     * Ontotext GraphDB at {@code <serverUrl>/repositories/<repositoryId>}.
     *
     * <p>Spring Boot 4 change: {@link StringUtils} moved to
     * {@code org.springframework.util} (same package as Boot 3 — no change needed).
     * The {@code @Bean} lifecycle is unchanged; {@code destroyMethod = "shutDown"}
     * calls {@link Repository#shutDown()} on application context close.
     */
    @Bean(destroyMethod = "shutDown")
    public @NonNull Repository graphDbRepository() {
        String repoUrl = props.getServerUrl()
                + "/repositories/"
                + props.getRepositoryId();

        log.info("Connecting to GraphDB repository at {}", repoUrl);

        HTTPRepository repo = new HTTPRepository(repoUrl);

        // Credentials are optional — skip when GraphDB security is disabled
        if (StringUtils.hasText(props.getUsername())) {
            repo.setUsernameAndPassword(props.getUsername(), props.getPassword());
        }

        // RDF4J 5.3.0: setHttpTimeout() is still the correct API for the HTTP client
        repo.setHttpTimeout(props.getReadTimeoutMs());
        repo.init();

        log.info("GraphDB repository initialised successfully: {}", repoUrl);
        return repo;
    }
}
