package com.example.graphdb;

import com.example.graphdb.config.GraphDbProperties;
import com.example.graphdb.model.Person;
import com.example.graphdb.repository.impl.PersonRepositoryImpl;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PersonRepositoryImpl} using an in-memory RDF4J
 * {@link MemoryStore} — no live Ontotext GraphDB instance required.
 *
 * <h3>Upgrade notes — Spring Boot 4.0.6 / RDF4J 5.3.0 / Java 25</h3>
 * <ul>
 *   <li><strong>JUnit 4 removed in Boot 4</strong>: This test suite was already
 *       on JUnit Jupiter ({@code org.junit.jupiter.api.*}) — no changes required.
 *       {@code @MockBean} / {@code @SpyBean} were removed in Boot 4 (deprecated 3.4);
 *       use {@code Mockito.mock()} or {@code @ExtendWith(MockitoExtension.class)} if
 *       mocking Spring beans is needed.</li>
 *   <li><strong>Mockito 5.x</strong> (pulled by {@code spring-boot-starter-test} in
 *       Boot 4) — no source-level changes needed for basic stubbing.</li>
 *   <li><strong>RDF4J 5.3.0 MemoryStore</strong>: API and behaviour unchanged;
 *       still the correct in-process test double for SPARQL-based repos.</li>
 *   <li><strong>Java 25</strong>: {@code List.of} / {@code .toList()} used throughout;
 *       no deprecations triggered at the Java 25 compiler level.</li>
 *   <li><strong>AssertJ 3.x</strong>: Still bundled in Boot 4 starter-test.</li>
 * </ul>
 */
class PersonRepositoryImplTest {

    private Repository inMemoryRepo;
    private PersonRepositoryImpl personRepo;

    @BeforeEach
    void setUp() {
        // 1. In-memory RDF4J Sail — no server needed (RDF4J 5.3.0 compatible)
        inMemoryRepo = new SailRepository(new MemoryStore());
        inMemoryRepo.init();

        // 2. Manually wire dependencies (no Spring context required for unit tests)
        personRepo = new PersonRepositoryImpl();
        ReflectionTestUtils.setField(personRepo, "repository", inMemoryRepo);

        GraphDbProperties props = new GraphDbProperties();
        props.setBaseUri("http://example.com/");
        ReflectionTestUtils.setField(personRepo, "props", props);
    }

    @AfterEach
    void tearDown() {
        inMemoryRepo.shutDown();
    }

    // ─── save / findById ──────────────────────────────────────────────────────

    @Test
    @DisplayName("save() persists a Person and findById() retrieves it")
    void saveAndFindById() {
        Person alice = Person.builder()
                .id("alice")
                .firstName("Alice")
                .lastName("Smith")
                .email("alice@example.com")
                .age(30)
                .build();

        personRepo.save(alice);

        Optional<Person> result = personRepo.findById("alice");
        assertThat(result).isPresent();
        assertThat(result.get().getFirstName()).isEqualTo("Alice");
        assertThat(result.get().getLastName()).isEqualTo("Smith");
        assertThat(result.get().getEmail()).isEqualTo("alice@example.com");
        assertThat(result.get().getAge()).isEqualTo(30);
    }

    @Test
    @DisplayName("findById() returns empty Optional for unknown id")
    void findByIdNotFound() {
        assertThat(personRepo.findById("nobody")).isEmpty();
    }

    // ─── existsById ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("existsById() returns true after save, false before")
    void existsById() {
        Person bob = Person.builder().id("bob").firstName("Bob").build();

        assertThat(personRepo.existsById("bob")).isFalse();
        personRepo.save(bob);
        assertThat(personRepo.existsById("bob")).isTrue();
    }

    // ─── count ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("count() reflects the number of saved persons")
    void count() {
        assertThat(personRepo.count()).isEqualTo(0L);
        personRepo.save(Person.builder().id("p1").firstName("One").build());
        personRepo.save(Person.builder().id("p2").firstName("Two").build());
        assertThat(personRepo.count()).isEqualTo(2L);
    }

    // ─── deleteById ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteById() removes the person from the store")
    void deleteById() {
        personRepo.save(Person.builder().id("carol").firstName("Carol").build());
        assertThat(personRepo.existsById("carol")).isTrue();

        personRepo.deleteById("carol");
        assertThat(personRepo.existsById("carol")).isFalse();
    }

    // ─── findByLastName ───────────────────────────────────────────────────────

    @Test
    @DisplayName("findByLastName() returns only matching persons")
    void findByLastName() {
        personRepo.save(Person.builder().id("d1").firstName("Dave").lastName("Jones").build());
        personRepo.save(Person.builder().id("d2").firstName("Dana").lastName("Jones").build());
        personRepo.save(Person.builder().id("d3").firstName("Eve").lastName("Brown").build());

        List<Person> jones = personRepo.findByLastName("Jones");
        assertThat(jones).hasSize(2)
                .extracting(Person::getLastName)
                .containsOnly("Jones");
    }

    // ─── findByAgeBetween ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findByAgeBetween() returns persons within the age range")
    void findByAgeBetween() {
        personRepo.save(Person.builder().id("young").firstName("Young").age(20).build());
        personRepo.save(Person.builder().id("mid").firstName("Mid").age(35).build());
        personRepo.save(Person.builder().id("old").firstName("Old").age(60).build());

        List<Person> inRange = personRepo.findByAgeBetween(25, 50);
        assertThat(inRange).hasSize(1)
                .extracting(Person::getAge)
                .containsExactly(35);
    }

    // ─── countByAgeGreaterThan ────────────────────────────────────────────────

    @Test
    @DisplayName("countByAgeGreaterThan() returns correct count")
    void countByAgeGreaterThan() {
        personRepo.save(Person.builder().id("a1").firstName("A").age(25).build());
        personRepo.save(Person.builder().id("a2").firstName("B").age(45).build());
        personRepo.save(Person.builder().id("a3").firstName("C").age(65).build());

        assertThat(personRepo.countByAgeGreaterThan(30)).isEqualTo(2L);
        assertThat(personRepo.countByAgeGreaterThan(60)).isEqualTo(1L);
        assertThat(personRepo.countByAgeGreaterThan(70)).isEqualTo(0L);
    }

    // ─── foaf:knows relationship ──────────────────────────────────────────────

    @Test
    @DisplayName("foaf:knows triples are stored and queried via findKnownBy()")
    void knowsRelationship() {
        Person alice = Person.builder()
                .id("alice2")
                .firstName("Alice")
                .knowsIris(List.of("http://example.com/persons/bob2"))
                .build();
        Person bob = Person.builder().id("bob2").firstName("Bob").build();

        personRepo.save(bob);
        personRepo.save(alice);

        List<Person> aliceKnows = personRepo.findKnownBy("alice2");
        assertThat(aliceKnows).hasSize(1)
                .extracting(Person::getFirstName)
                .containsExactly("Bob");
    }
}
