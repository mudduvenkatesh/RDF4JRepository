package com.example.graphdb.repository.impl;

import com.example.graphdb.config.GraphDbProperties;
import com.example.graphdb.model.Person;
import com.example.graphdb.repository.PersonRepository;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Concrete Spring {@link Repository} implementation of {@link PersonRepository}
 * backed by Ontotext GraphDB via the RDF4J 5.3.0 HTTP connector.
 *
 * <p>All persistence is done through SPARQL INSERT DATA / DELETE / SELECT
 * using the {@code foaf:} vocabulary.
 *
 * <h3>Upgrade notes — Spring Boot 4.0.6 / RDF4J 5.3.0 / Java 25</h3>
 * <ul>
 *   <li><strong>JSpecify</strong>: {@code @NonNull} / {@code @Nullable} added to
 *       all public API signatures. NullAway enforces these at build time.</li>
 *   <li><strong>RDF4J 5.3.0</strong>: {@code Var} constructors are deprecated;
 *       use {@code Var.of(...)} factories if extending the algebra layer.
 *       The HTTP Repository API used here is unchanged.</li>
 *   <li><strong>Java 25 / SequencedCollection</strong>: {@code rows.getFirst()}
 *       replaces {@code rows.get(0)} (introduced Java 21, stable in Java 25).</li>
 *   <li><strong>Jakarta EE 11</strong>: No {@code javax.*} imports — already clean.</li>
 * </ul>
 *
 * <p>RDF type managed: {@code foaf:Person}
 */
@Slf4j
@Repository
public class PersonRepositoryImpl
        extends AbstractGraphDbRepository<Person, String>
        implements PersonRepository {

    // ─── FOAF Vocabulary Constants ────────────────────────────────────────────

    private static final String FOAF            = "http://xmlns.com/foaf/0.1/";
    private static final String FOAF_PERSON     = FOAF + "Person";

    @Autowired
    private @NonNull GraphDbProperties props;

    // ─── AbstractGraphDbRepository contract ──────────────────────────────────

    @Override
    protected @NonNull String getRdfTypeIri() { return FOAF_PERSON; }

    @Override
    protected @NonNull String getBaseNamespace() {
        return props.getBaseUri() + "persons/";
    }

    @Override
    protected @NonNull String buildDescribeQuery(@NonNull String iri) {
        return getPrefixBlock() + String.format("""
                SELECT ?firstName ?lastName ?email ?age ?knows
                WHERE {
                  <%s> a foaf:Person .
                  OPTIONAL { <%s> foaf:firstName ?firstName }
                  OPTIONAL { <%s> foaf:lastName  ?lastName  }
                  OPTIONAL { <%s> foaf:mbox      ?email     }
                  OPTIONAL { <%s> foaf:age       ?age       }
                  OPTIONAL { <%s> foaf:knows     ?knows     }
                }
                """, iri, iri, iri, iri, iri, iri);
    }

    @Override
    protected @Nullable Person mapRow(@NonNull List<Map<String, String>> rows) {
        if (rows.isEmpty()) return null;

        Map<String, String> first = rows.getFirst(); // Java 21+ SequencedCollection

        List<String> knowsIris = rows.stream()
                .map(r -> r.get("knows"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Integer age = null;
        String rawAge = first.get("age");
        if (rawAge != null) {
            try { age = Integer.parseInt(rawAge.replaceAll("[^\\d]", "")); }
            catch (NumberFormatException ignored) { }
        }

        return Person.builder()
                .firstName(first.get("firstName"))
                .lastName(first.get("lastName"))
                .email(first.get("email"))
                .age(age)
                .knowsIris(knowsIris)
                .build();
    }

    @Override
    protected @Nullable Person mapBindingSetToEntity(@NonNull BindingSet bs) {
        throw new UnsupportedOperationException("Use mapRow()");
    }

    @Override
    protected @NonNull String entityToTriples(@NonNull Person p) {
        String iri = (p.getIri() != null) ? p.getIri() : buildIri(p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  <%s> a foaf:Person", iri));

        if (p.getFirstName() != null)
            sb.append(String.format(" ;\n    foaf:firstName \"%s\"",
                    escapeSparqlLiteral(p.getFirstName())));
        if (p.getLastName() != null)
            sb.append(String.format(" ;\n    foaf:lastName  \"%s\"",
                    escapeSparqlLiteral(p.getLastName())));
        if (p.getEmail() != null)
            sb.append(String.format(" ;\n    foaf:mbox      \"%s\"",
                    escapeSparqlLiteral(p.getEmail())));
        if (p.getAge() != null)
            sb.append(String.format(" ;\n    foaf:age       \"%d\"^^xsd:integer", p.getAge()));
        if (p.getKnowsIris() != null)
            for (String kIri : p.getKnowsIris())
                sb.append(String.format(" ;\n    foaf:knows     <%s>", kIri));

        sb.append(" .\n");
        return sb.toString();
    }

    // ─── CrudRepository — findById / delete ──────────────────────────────────

    @Override
    public @NonNull Optional<Person> findById(@NonNull String id) {
        String iri = buildIri(id);
        if (!existsById(id)) return Optional.empty();

        List<Map<String, String>> rows = executeSparqlSelect(
                buildDescribeQuery(iri), Collections.emptyMap());

        Person p = mapRow(rows);
        if (p != null) { p.setId(id); p.setIri(iri); }
        return Optional.ofNullable(p);
    }

    @Override
    public void delete(@NonNull Person entity) { deleteById(entity.getId()); }

    // ─── PersonRepository — domain queries ───────────────────────────────────

    @Override
    public @NonNull List<Person> findByFirstName(@NonNull String firstName) {
        String sparql = getPrefixBlock() + """
                SELECT ?s WHERE {
                  ?s a foaf:Person ; foaf:firstName ?fn .
                  FILTER(LCASE(STR(?fn)) = LCASE(":firstName"))
                }""";
        return queryPersonsBySubjectList(sparql, Map.of("firstName", firstName));
    }

    @Override
    public @NonNull List<Person> findByLastName(@NonNull String lastName) {
        String sparql = getPrefixBlock() + """
                SELECT ?s WHERE {
                  ?s a foaf:Person ; foaf:lastName ":lastName" .
                }""";
        return queryPersonsBySubjectList(sparql, Map.of("lastName", lastName));
    }

    @Override
    public @NonNull List<Person> findByAgeBetween(int minAge, int maxAge) {
        String sparql = getPrefixBlock() + String.format("""
                SELECT ?s WHERE {
                  ?s a foaf:Person ; foaf:age ?age .
                  FILTER(?age >= %d && ?age <= %d)
                }""", minAge, maxAge);
        return queryPersonsBySubjectList(sparql, Collections.emptyMap());
    }

    @Override
    public @NonNull List<Person> findKnownBy(@NonNull String personId) {
        String sparql = getPrefixBlock() + String.format("""
                SELECT ?s WHERE {
                  <%s> foaf:knows ?s . ?s a foaf:Person .
                }""", buildIri(personId));
        return queryPersonsBySubjectList(sparql, Collections.emptyMap());
    }

    @Override
    public long countByAgeGreaterThan(int age) {
        String sparql = getPrefixBlock() + String.format("""
                SELECT (COUNT(DISTINCT ?s) AS ?count) WHERE {
                  ?s a foaf:Person ; foaf:age ?age . FILTER(?age > %d)
                }""", age);
        List<Map<String, String>> rows = executeSparqlSelect(sparql, Collections.emptyMap());
        if (rows.isEmpty()) return 0L;
        return Long.parseLong(rows.getFirst().getOrDefault("count", "0").replaceAll("[^\\d]", ""));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private @NonNull List<Person> queryPersonsBySubjectList(
            @NonNull String sparql, @NonNull Map<String, String> params) {

        List<Person> results = new ArrayList<>();
        for (Map<String, String> row : executeSparqlSelect(sparql, params)) {
            String iri = row.get("s");
            if (iri == null) continue;
            findById(iri.substring(iri.lastIndexOf('/') + 1)).ifPresent(results::add);
        }
        return results;
    }
}
