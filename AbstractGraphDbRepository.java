package com.example.graphdb.repository.impl;

import com.example.graphdb.exception.GraphRepositoryException;
import com.example.graphdb.repository.GraphDbRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

/**
 * Abstract base class that wires the RDF4J {@link Repository} and provides
 * shared SPARQL execution helpers used by all concrete repositories.
 *
 * <p>Concrete subclasses supply:
 * <ul>
 *   <li>The RDF type IRI for their domain objects ({@link #getRdfTypeIri()})</li>
 *   <li>The base namespace IRI for resource minting ({@link #getBaseNamespace()})</li>
 *   <li>Mapping methods: {@link #mapBindingSetToEntity} and {@link #entityToTriples}</li>
 * </ul>
 *
 * <h3>Upgrade notes — Spring Boot 4.0.6 / RDF4J 5.3.0 / Java 25</h3>
 * <ul>
 *   <li><strong>Spring Boot 4 / Framework 7</strong>: Jakarta EE 11 baseline;
 *       {@code org.springframework.lang.@Nullable} replaced with JSpecify
 *       {@code org.jspecify.annotations.@Nullable} throughout.</li>
 *   <li><strong>RDF4J 5.3.0</strong>: {@link TupleQueryResult} implements
 *       {@link Iterable} and is auto-closeable — usage unchanged.
 *       {@code Var.of(...)} factory replaces deprecated constructors in the
 *       algebra layer (not used directly here, but relevant for custom query
 *       builders).</li>
 *   <li><strong>Java 25</strong>: {@code List.copyOf} / {@code .toList()} streams
 *       already used. {@link SequencedCollection} (Java 21+) is now the correct
 *       type for ordered result lists; concrete type remains {@link ArrayList}
 *       for mutability during accumulation.</li>
 * </ul>
 *
 * @param <T>  domain type
 * @param <ID> local identifier type
 */
@Slf4j
public abstract class AbstractGraphDbRepository<T, ID> implements GraphDbRepository<T, ID> {

    @Autowired
    protected @NonNull Repository repository;

    // ─── Subclass Contract ────────────────────────────────────────────────────

    /** Full IRI string for the RDF type, e.g. {@code http://xmlns.com/foaf/0.1/Person} */
    protected abstract @NonNull String getRdfTypeIri();

    /** Base namespace used when building resource IRIs, e.g. {@code http://example.com/persons/} */
    protected abstract @NonNull String getBaseNamespace();

    /**
     * Map a SPARQL {@link BindingSet} row (a single resource's bindings)
     * into a domain object.
     */
    protected abstract @Nullable T mapBindingSetToEntity(@NonNull BindingSet bs);

    /**
     * Produce the SPARQL INSERT DATA block body (triples only, no wrapper)
     * for the given entity.
     *
     * <p>Example output:
     * <pre>
     *   &lt;http://example.com/persons/alice&gt; rdf:type foaf:Person ;
     *       foaf:name "Alice" .
     * </pre>
     */
    protected abstract @NonNull String entityToTriples(@NonNull T entity);

    /**
     * Return the IRI string for a given local id, e.g.
     * {@code http://example.com/persons/alice}
     */
    protected @NonNull String buildIri(@NonNull ID id) {
        return getBaseNamespace() + id;
    }

    // ─── GraphDbRepository — Generic CRUD ────────────────────────────────────

    @Override
    public @NonNull T save(@NonNull T entity) {
        String triples = entityToTriples(entity);
        String update = "INSERT DATA { " + triples + " }";
        executeSparqlUpdate(update, Collections.emptyMap());
        log.debug("Saved entity with triples:\n{}", triples);
        return entity;
    }

    @Override
    public @NonNull Iterable<T> saveAll(@NonNull Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        StringBuilder sb = new StringBuilder("INSERT DATA {\n");
        for (T entity : entities) {
            sb.append(entityToTriples(entity)).append("\n");
            saved.add(entity);
        }
        sb.append("}");
        executeSparqlUpdate(sb.toString(), Collections.emptyMap());
        log.debug("Batch-saved {} entities", saved.size());
        return saved;
    }

    @Override
    public boolean existsById(@NonNull ID id) {
        String iri = buildIri(id);
        String sparql = String.format(
                "ASK { <%s> a <%s> }", iri, getRdfTypeIri());

        try (RepositoryConnection conn = repository.getConnection()) {
            BooleanQuery q = conn.prepareBooleanQuery(QueryLanguage.SPARQL, sparql);
            return q.evaluate();
        } catch (RepositoryException | MalformedQueryException | QueryEvaluationException e) {
            throw new GraphRepositoryException("existsById failed for IRI: " + iri, e);
        }
    }

    @Override
    public @NonNull List<T> findAll() {
        return findAll(Integer.MAX_VALUE, 0);
    }

    @Override
    public @NonNull List<T> findAll(int limit, int offset) {
        String sparql = buildSelectAllQuery(limit, offset);
        return executeSparqlSelect(sparql, Collections.emptyMap())
                .stream()
                .map(this::mapStringMapToEntity)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public long count() {
        String sparql = String.format(
                "SELECT (COUNT(DISTINCT ?s) AS ?count) WHERE { ?s a <%s> }",
                getRdfTypeIri());

        List<Map<String, String>> rows = executeSparqlSelect(sparql, Collections.emptyMap());
        if (rows.isEmpty()) return 0L;
        String val = rows.getFirst().getOrDefault("count", "0");  // Java 21+ SequencedCollection
        // Strip ^^xsd:integer datatype annotation if present
        return Long.parseLong(val.contains("\"") ? val.replaceAll("\"(\\d+)\".*", "$1") : val);
    }

    @Override
    public void deleteById(@NonNull ID id) {
        String iri = buildIri(id);
        // Delete all triples where this IRI is subject OR object
        String update = String.format(
                "DELETE WHERE { <%s> ?p ?o }; DELETE WHERE { ?s ?p <%s> }",
                iri, iri);
        executeSparqlUpdate(update, Collections.emptyMap());
        log.debug("Deleted resource: {}", iri);
    }

    @Override
    public void deleteAll() {
        String update = String.format(
                "DELETE { ?s ?p ?o } WHERE { ?s a <%s> . ?s ?p ?o }",
                getRdfTypeIri());
        executeSparqlUpdate(update, Collections.emptyMap());
        log.warn("Deleted ALL resources of type <{}>", getRdfTypeIri());
    }

    // ─── SPARQL Execution Helpers ─────────────────────────────────────────────

    /**
     * Execute a SPARQL SELECT and return results as a list of variable→value maps.
     * Values are formatted as plain strings (literals have datatype/lang stripped).
     *
     * <p><strong>RDF4J 5.3.0</strong>: {@link TupleQueryResult} is iterable and
     * auto-closeable; the try-with-resources pattern here is unchanged and correct.
     */
    @Override
    public @NonNull List<Map<String, String>> executeSparqlSelect(
            @NonNull String sparql, @NonNull Map<String, String> parameters) {

        String resolved = resolvePlaceholders(sparql, parameters);
        log.debug("SPARQL SELECT:\n{}", resolved);

        List<Map<String, String>> results = new ArrayList<>();
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, resolved);
            try (TupleQueryResult result = query.evaluate()) {
                for (BindingSet bs : result) {
                    // LinkedHashMap preserves SPARQL projection order (Java 25: unchanged)
                    Map<String, String> row = new LinkedHashMap<>();
                    bs.getBindingNames().forEach(name ->
                            row.put(name, bs.hasBinding(name)
                                    ? bs.getValue(name).stringValue()
                                    : null));
                    results.add(row);
                }
            }
        } catch (RepositoryException | MalformedQueryException | QueryEvaluationException e) {
            throw new GraphRepositoryException("SPARQL SELECT failed: " + e.getMessage(), e);
        }
        return results;
    }

    /** Execute a SPARQL UPDATE (INSERT/DELETE) statement. */
    @Override
    public void executeSparqlUpdate(
            @NonNull String sparql, @NonNull Map<String, String> parameters) {

        String resolved = resolvePlaceholders(sparql, parameters);
        log.debug("SPARQL UPDATE:\n{}", resolved);

        try (RepositoryConnection conn = repository.getConnection()) {
            conn.prepareUpdate(QueryLanguage.SPARQL, prependPrefixes(resolved)).execute();
        } catch (RepositoryException | MalformedQueryException | UpdateExecutionException e) {
            throw new GraphRepositoryException("SPARQL UPDATE failed: " + e.getMessage(), e);
        }
    }

    // ─── Internal Utilities ───────────────────────────────────────────────────

    /**
     * Builds a generic SELECT query for all resources of this type.
     * Subclasses should override this to select their specific properties.
     */
    protected @NonNull String buildSelectAllQuery(int limit, int offset) {
        return String.format(
                "%s SELECT ?s WHERE { ?s a <%s> } LIMIT %d OFFSET %d",
                getPrefixBlock(), getRdfTypeIri(), limit, offset);
    }

    /**
     * Map a String-keyed row (from {@link #executeSparqlSelect}) to a domain entity.
     * Default implementation re-fetches the full resource by IRI.
     * Subclasses may override for efficiency.
     */
    protected @Nullable T mapStringMapToEntity(@NonNull Map<String, String> row) {
        String iri = row.get("s");
        if (iri == null) throw new GraphRepositoryException(
                "SELECT result missing ?s binding (subject IRI)");
        return findByIri(iri);
    }

    /**
     * Fetch all triples for a given subject IRI and map to a domain object.
     * Used by the default {@link #mapStringMapToEntity} and by {@link #findById}.
     */
    protected @Nullable T findByIri(@NonNull String iri) {
        String sparql = buildDescribeQuery(iri);
        List<Map<String, String>> rows = executeSparqlSelect(sparql, Collections.emptyMap());
        if (rows.isEmpty()) return null;
        return mapRow(rows);
    }

    /**
     * Build a SPARQL SELECT that fetches all predicates/objects for a subject IRI.
     * Subclasses typically override this to project named variables they care about.
     */
    protected @NonNull String buildDescribeQuery(@NonNull String iri) {
        return String.format(
                "%s SELECT ?p ?o WHERE { <%s> ?p ?o }", getPrefixBlock(), iri);
    }

    /**
     * Map a list of predicate-object rows for a single subject into a domain entity.
     * Subclasses must override this when using the default describe-query strategy.
     */
    protected @Nullable T mapRow(@NonNull List<Map<String, String>> rows) {
        throw new UnsupportedOperationException(
                "Override mapRow() or findByIri() in " + getClass().getSimpleName());
    }

    /** Substitute {@code :paramName} placeholders with escaped literal values. */
    protected @NonNull String resolvePlaceholders(
            @NonNull String sparql, @NonNull Map<String, String> params) {

        String result = sparql;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            result = result.replace(":" + entry.getKey(),
                    "\"" + escapeSparqlLiteral(entry.getValue()) + "\"");
        }
        return result;
    }

    protected @NonNull String escapeSparqlLiteral(@Nullable String value) {
        return value == null ? ""
                : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Common RDF / FOAF namespace prefixes prepended to every SPARQL query. */
    protected @NonNull String getPrefixBlock() {
        return """
                PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                PREFIX ex:   <http://example.com/>
                """;
    }

    private @NonNull String prependPrefixes(@NonNull String sparql) {
        return sparql.startsWith("PREFIX") ? sparql : getPrefixBlock() + sparql;
    }

    /** Convenience: ValueFactory from the underlying repository. */
    protected @NonNull ValueFactory valueFactory() {
        return repository.getValueFactory();
    }

    protected @NonNull IRI iri(@NonNull String fullIri) {
        return valueFactory().createIRI(fullIri);
    }
}
