package com.example.graphdb.config;

import lombok.Data;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration properties bound from {@code application.properties}.
 * All keys are prefixed with {@code graphdb.*}.
 *
 * <h3>Spring Boot 4 notes</h3>
 * <ul>
 *   <li>JSpecify {@code @NonNull} / {@code @Nullable} are used instead of the
 *       deprecated {@code org.springframework.lang} equivalents.</li>
 *   <li>{@link ConfigurationProperties} binding behaviour is unchanged.</li>
 *   <li>{@code @EnableConfigurationProperties} is no longer required when
 *       {@code @Component} is present — Boot 4 auto-detects it.</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "graphdb")
public class GraphDbProperties {

    /** Full URL of the Ontotext GraphDB server, e.g. {@code http://localhost:7200} */
    private @NonNull String serverUrl = "http://localhost:7200";

    /** The repository ID to connect to, e.g. {@code "my-repo"} */
    private @NonNull String repositoryId = "";

    /**
     * Optional username — leave blank when GraphDB security is disabled.
     * {@code @Nullable} because an empty/absent value is valid.
     */
    private @Nullable String username;

    /** Optional password — leave blank when GraphDB security is disabled. */
    private @Nullable String password;

    /** Base URI used when minting new resource IRIs */
    private @NonNull String baseUri = "http://example.com/";

    /** HTTP connection timeout in milliseconds */
    private int connectionTimeoutMs = 5_000;

    /** HTTP read/query timeout in milliseconds */
    private int readTimeoutMs = 30_000;
}
