package org.instruct.jobenginespring.application.profile;

import org.flywaydb.core.Flyway;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfilePdfSourceRepository;
import org.instruct.jobenginespring.adapter.out.postgres.profile.PostgresProfileRepository;
import org.instruct.jobenginespring.application.document.DocumentStorageService;
import org.instruct.jobenginespring.application.document.DocumentStorageService.StoredPdfTextExtractionResult;
import org.instruct.jobenginespring.application.document.GeneratedResumeAssetService;
import org.instruct.jobenginespring.application.document.PdfTextExtractionService.PdfTextExtractionResult;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestProfileFromStoredPdfRequest;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.IngestionStatus;
import org.instruct.jobenginespring.application.profile.ProfilePdfIngestionService.ProfilePdfIngestionResult;
import org.instruct.jobenginespring.application.profile.ProfileService.LinkWriteRequest;
import org.instruct.jobenginespring.application.profile.ProfileService.ProfileWriteRequest;
import org.instruct.jobenginespring.application.profile.port.ProfileTextExtractor;
import org.instruct.jobenginespring.domain.document.StoredDocumentMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ProfilePdfIngestionConcurrencyIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-14T15:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("job_engine")
            .withUsername("test")
            .withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    private PostgresProfileRepository profiles;
    private PostgresProfilePdfSourceRepository sources;
    private ProfileService profileService;

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .defaultSchema("profile")
                .schemas("profile", "document", "job_schema")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE profile.profiles, document.blobs CASCADE");
        var namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        profiles = new PostgresProfileRepository(namedJdbc);
        sources = new PostgresProfilePdfSourceRepository(JdbcClient.create(namedJdbc));
        profileService = new ProfileService(profiles, mock(GeneratedResumeAssetService.class));
    }

    @Test
    void sameExtractionProducesOneCreateAndOneReuse() throws Exception {
        StoredPdfTextExtractionResult extraction = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
        var blockingSources = installBlockingSources(org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                "document:" + extraction.document().sha256()
        ));
        ProfilePdfIngestionService service = serviceFor(extraction, extraction, profileRequest());

        List<ProfilePdfIngestionResult> results = runAtContestedLock(
                service,
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), null, null, null),
                new IngestProfileFromStoredPdfRequest(extraction.document().id(), null, null, null),
                blockingSources
        );

        assertEquals(Set.of(IngestionStatus.CREATED_PROFILE, IngestionStatus.REUSED_EXISTING_SOURCE), statuses(results));
        assertEquals(List.of(1, 2), blockingSources.acquiredOrder());
        assertReturnedRelationshipsExist(results);
        assertEquals(1, count("profile.profiles"));
        assertEquals(1, count("profile.profile_pdf_sources"));
    }

    @Test
    void sameBytesAcrossExtractionsProduceOneCreateAndOneReuse() throws Exception {
        String sha256 = "f".repeat(64);
        StoredPdfTextExtractionResult first = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), sha256);
        StoredPdfTextExtractionResult second = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), sha256);
        ProfilePdfIngestionService service = serviceFor(first, second, profileRequest());

        List<ProfilePdfIngestionResult> results = runConcurrently(
                service,
                new IngestProfileFromStoredPdfRequest(first.document().id(), null, null, null),
                new IngestProfileFromStoredPdfRequest(second.document().id(), null, null, null)
        );

        assertEquals(Set.of(IngestionStatus.CREATED_PROFILE, IngestionStatus.REUSED_EXISTING_SOURCE), statuses(results));
        assertReturnedRelationshipsExist(results);
        assertEquals(1, count("profile.profiles"));
        assertEquals(1, count("profile.profile_pdf_sources"));
    }

    @Test
    void sameCanonicalEmailProducesOneCreateAndOneDuplicateCandidate() throws Exception {
        StoredPdfTextExtractionResult first = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64));
        StoredPdfTextExtractionResult second = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64));
        ProfileWriteRequest firstRequest = profileRequest("agent@example.test", "https://example.test/first");
        ProfileWriteRequest secondRequest = profileRequest("agent@example.test", "https://example.test/second");
        ProfilePdfIngestionService service = serviceFor(first, second, firstRequest, secondRequest);

        assertDuplicateCandidatePair(service, first, second);
    }

    @Test
    void sameCanonicalProfileLinkProducesOneCreateAndOneDuplicateCandidate() throws Exception {
        StoredPdfTextExtractionResult first = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "1".repeat(64));
        StoredPdfTextExtractionResult second = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "2".repeat(64));
        ProfileWriteRequest firstRequest = profileRequest("first@example.test", "example.test/shared");
        ProfileWriteRequest secondRequest = profileRequest("second@example.test", "HTTP://EXAMPLE.test/shared/?q=1#bio");
        List<String> firstKeys = new ProfileIdentityMatcher(profiles).concurrencyKeys(firstRequest);
        String contestedKey = new ProfileIdentityMatcher(profiles).concurrencyKeys(secondRequest).stream()
                .filter(firstKeys::contains)
                .findFirst()
                .orElseThrow();
        var blockingSources = new BlockingProfilePdfSourceRepository(
                JdbcClient.create(new NamedParameterJdbcTemplate(dataSource)), contestedKey
        );
        sources = blockingSources;
        ProfilePdfIngestionService service = serviceFor(first, second, firstRequest, secondRequest);

        List<ProfilePdfIngestionResult> results = runAtContestedLock(
                service,
                new IngestProfileFromStoredPdfRequest(first.document().id(), null, null, null),
                new IngestProfileFromStoredPdfRequest(second.document().id(), null, null, null),
                blockingSources
        );

        assertEquals(Set.of(IngestionStatus.CREATED_PROFILE, IngestionStatus.DUPLICATE_PROFILE_CANDIDATE), statuses(results));
        assertEquals(List.of(1, 2), blockingSources.acquiredOrder());
        assertReturnedRelationshipsExist(results);
        assertEquals(1, count("profile.profiles"));
        assertEquals(1, count("profile.profile_pdf_sources"));
        assertEquals("https://example.test/shared", jdbc.queryForObject(
                "SELECT url FROM profile.profile_links", String.class
        ));
    }

    private static void assertDuplicateCandidatePair(
            ProfilePdfIngestionService service,
            StoredPdfTextExtractionResult first,
            StoredPdfTextExtractionResult second
    ) throws Exception {

        List<ProfilePdfIngestionResult> results = runConcurrently(
                service,
                new IngestProfileFromStoredPdfRequest(first.document().id(), null, null, null),
                new IngestProfileFromStoredPdfRequest(second.document().id(), null, null, null)
        );

        assertEquals(Set.of(IngestionStatus.CREATED_PROFILE, IngestionStatus.DUPLICATE_PROFILE_CANDIDATE), statuses(results));
        assertReturnedRelationshipsExist(results);
        assertEquals(1, count("profile.profiles"));
        assertEquals(1, count("profile.profile_pdf_sources"));
    }

    @Test
    void sameTargetProfileProducesOneUpdateAndOneSanitizedDuplicate() throws Exception {
        UUID profileId = profileService.createProfile(profileRequest()).profile().id();
        StoredPdfTextExtractionResult first = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "d".repeat(64));
        StoredPdfTextExtractionResult second = seedExtraction(UUID.randomUUID(), UUID.randomUUID(), "e".repeat(64));
        var blockingSources = installBlockingSources(org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                "profile:" + profileId
        ));
        ProfilePdfIngestionService service = serviceFor(
                first,
                second,
                profileRequest("first-target@example.test", "https://example.test/first-target"),
                profileRequest("second-target@example.test", "https://example.test/second-target")
        );

        List<ProfilePdfIngestionResult> results = runAtContestedLock(
                service,
                new IngestProfileFromStoredPdfRequest(first.document().id(), profileId, true, null, 0L),
                new IngestProfileFromStoredPdfRequest(second.document().id(), profileId, true, null, 0L),
                blockingSources
        );

        assertEquals(Set.of(IngestionStatus.UPDATED_PROFILE, IngestionStatus.DUPLICATE_PROFILE_CANDIDATE), statuses(results));
        assertEquals(List.of(1, 2), blockingSources.acquiredOrder());
        assertReturnedRelationshipsExist(results);
        assertEquals(1, profiles.findProfileById(profileId).orElseThrow().revision());
        assertEquals(1, count("profile.profile_pdf_sources"));
    }

    private ProfilePdfIngestionService serviceFor(
            StoredPdfTextExtractionResult first,
            StoredPdfTextExtractionResult second,
            ProfileWriteRequest request
    ) {
        return serviceFor(first, second, request, request);
    }

    private BlockingProfilePdfSourceRepository installBlockingSources(String contestedKey) {
        var blocking = new BlockingProfilePdfSourceRepository(
                JdbcClient.create(new NamedParameterJdbcTemplate(dataSource)), contestedKey
        );
        sources = blocking;
        return blocking;
    }

    private ProfilePdfIngestionService serviceFor(
            StoredPdfTextExtractionResult first,
            StoredPdfTextExtractionResult second,
            ProfileWriteRequest firstRequest,
            ProfileWriteRequest secondRequest
    ) {
        DocumentStorageService documents = mock(DocumentStorageService.class);
        when(documents.extractStoredPdfText(any())).thenAnswer(invocation -> {
            UUID documentId = invocation.<DocumentStorageService.ExtractStoredPdfTextRequest>getArgument(0).documentId();
            return first.document().id().equals(documentId) ? first : second;
        });
        ProfileTextExtractor extractor = mock(ProfileTextExtractor.class);
        when(extractor.extractProfile(any())).thenAnswer(invocation -> {
            ProfileTextExtractor.ProfileTextExtractionInput input = invocation.getArgument(0);
            return first.extraction().text().equals(input.text()) ? firstRequest : secondRequest;
        });
        return new ProfilePdfIngestionService(
                documents,
                extractor,
                profileService,
                new ProfileIdentityMatcher(profiles),
                sources
        );
    }

    private static List<ProfilePdfIngestionResult> runConcurrently(
            ProfilePdfIngestionService service,
            IngestProfileFromStoredPdfRequest first,
            IngestProfileFromStoredPdfRequest second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProfilePdfIngestionResult> firstResult = executor.submit(() -> transactions.execute(status -> {
                ready.countDown();
                await(start);
                return service.ingestProfileFromStoredPdf(first);
            }));
            Future<ProfilePdfIngestionResult> secondResult = executor.submit(() -> transactions.execute(status -> {
                ready.countDown();
                await(start);
                return service.ingestProfileFromStoredPdf(second);
            }));
            ready.await();
            start.countDown();
            return List.of(firstResult.get(), secondResult.get());
        }
    }

    private static List<ProfilePdfIngestionResult> runAtContestedLock(
            ProfilePdfIngestionService service,
            IngestProfileFromStoredPdfRequest first,
            IngestProfileFromStoredPdfRequest second,
            BlockingProfilePdfSourceRepository blockingSources
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProfilePdfIngestionResult> firstResult = executor.submit(() -> transactions.execute(status -> {
                ready.countDown();
                await(start);
                return service.ingestProfileFromStoredPdf(first);
            }));
            Future<ProfilePdfIngestionResult> secondResult = executor.submit(() -> transactions.execute(status -> {
                ready.countDown();
                await(start);
                return service.ingestProfileFromStoredPdf(second);
            }));
            await(ready);
            start.countDown();
            try {
                await(blockingSources.firstLockHeld);
                await(blockingSources.secondLockAttempted);
                assertFalse(firstResult.isDone(), blockingSources.diagnostic());
                assertFalse(secondResult.isDone(), blockingSources.diagnostic());
            } finally {
                blockingSources.allowFirstWriter.countDown();
            }
            return List.of(firstResult.get(), secondResult.get());
        }
    }

    private static StoredPdfTextExtractionResult seedExtraction(UUID documentId, UUID extractionId, String sha256) {
        List<UUID> blobIds = jdbc.queryForList(
                "SELECT id FROM document.blobs WHERE sha256 = ?", UUID.class, sha256
        );
        UUID blobId = blobIds.isEmpty() ? UUID.randomUUID() : blobIds.getFirst();
        if (blobIds.isEmpty()) {
            jdbc.update("""
                    INSERT INTO document.blobs (id, sha256, byte_size, content, created_at)
                    VALUES (?, ?, 4, ?, ?)
                    """, blobId, sha256, new byte[]{1, 2, 3, 4}, Timestamp.from(NOW));
        }
        jdbc.update("""
                INSERT INTO document.documents (id, blob_id, original_file_name, media_type, created_at, updated_at)
                VALUES (?, ?, 'resume.pdf', 'application/pdf', ?, ?)
                """, documentId, blobId, Timestamp.from(NOW), Timestamp.from(NOW));
        String extractedText = "Agentic Dev agent@example.test " + documentId;
        jdbc.update("""
                INSERT INTO document.pdf_extractions
                    (id, file_id, extractor, character_count, page_count, truncated, extracted_text, created_at)
                VALUES (?, ?, 'test', ?, 1, false, ?, ?)
                """, extractionId, documentId, extractedText.length(), extractedText, Timestamp.from(NOW));
        return new StoredPdfTextExtractionResult(
                new StoredDocumentMetadata(documentId, "resume.pdf", "application/pdf", 4, sha256, NOW, NOW),
                extractionId,
                new PdfTextExtractionResult("resume.pdf", 1, extractedText.length(), false, extractedText, List.of())
        );
    }

    private static ProfileWriteRequest profileRequest() {
        return profileRequest("agent@example.test", "https://example.test/agent");
    }

    private static ProfileWriteRequest profileRequest(String email, String profileLink) {
        return new ProfileWriteRequest(
                "Agentic Dev",
                email,
                null,
                List.of(),
                List.of(new LinkWriteRequest(null, "portfolio", profileLink, null)),
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static Set<IngestionStatus> statuses(List<ProfilePdfIngestionResult> results) {
        return results.stream().map(ProfilePdfIngestionResult::status).collect(java.util.stream.Collectors.toSet());
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void assertReturnedRelationshipsExist(List<ProfilePdfIngestionResult> results) {
        for (ProfilePdfIngestionResult result : results) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*)
                    FROM document.pdf_extractions
                    WHERE id = ? AND file_id = ?
                    """, Integer.class, result.pdfExtractionId(), result.documentId()));
            if (result.sourceLinkId() == null) {
                assertFalse(result.existingProfileLink());
                assertNull(result.profileId());
                continue;
            }
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*)
                    FROM profile.profile_pdf_sources
                    WHERE id = ? AND profile_id = ? AND pdf_extraction_id = ?
                    """, Integer.class, result.sourceLinkId(), result.profileId(), result.pdfExtractionId()));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrency boundary");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrency latch interrupted", exception);
        }
    }

    private static final class BlockingProfilePdfSourceRepository extends PostgresProfilePdfSourceRepository {

        private final String contestedKey;
        private final AtomicInteger attempts = new AtomicInteger();
        private final List<Integer> acquiredOrder = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final List<String> attemptThreads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final CountDownLatch firstLockHeld = new CountDownLatch(1);
        private final CountDownLatch secondLockAttempted = new CountDownLatch(1);
        private final CountDownLatch allowFirstWriter = new CountDownLatch(1);

        private BlockingProfilePdfSourceRepository(JdbcClient jdbc, String contestedKey) {
            super(jdbc);
            this.contestedKey = contestedKey;
        }

        @Override
        public void acquireIngestionLock(String hashedLockKey) {
            if (!contestedKey.equals(hashedLockKey)) {
                super.acquireIngestionLock(hashedLockKey);
                return;
            }
            int attempt = attempts.incrementAndGet();
            attemptThreads.add(attempt + ":" + Thread.currentThread().threadId());
            if (attempt == 2) {
                await(firstLockHeld);
                secondLockAttempted.countDown();
            }
            super.acquireIngestionLock(hashedLockKey);
            acquiredOrder.add(attempt);
            if (attempt == 1) {
                firstLockHeld.countDown();
                awaitUnbounded(allowFirstWriter);
            }
        }

        private List<Integer> acquiredOrder() {
            return List.copyOf(acquiredOrder);
        }

        private String diagnostic() {
            return "attempts=" + attempts.get() + ", acquired=" + acquiredOrder + ", threads=" + attemptThreads;
        }

        private static void awaitUnbounded(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrency latch interrupted", exception);
            }
        }
    }
}
