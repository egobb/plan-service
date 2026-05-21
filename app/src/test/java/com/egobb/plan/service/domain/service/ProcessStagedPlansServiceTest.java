package com.egobb.plan.service.domain.service;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.domain.model.plan.SellMode;
import com.egobb.plan.service.domain.port.PlanWriteRepository;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagedPlan;
import com.egobb.plan.service.infrastructure.persistence.postgres.ingest.StagingPlanPostgresRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessStagedPlansServiceTest {

	@Test
	void shouldUpsertAndMarkDoneWhenProcessingSucceeds() {
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);
		final PlanWriteRepository planWriteRepo = mock(PlanWriteRepository.class);

		final ProcessStagedPlansService svc = new ProcessStagedPlansService(stagingRepo, planWriteRepo, null);

		final UUID stagedId = UUID.randomUUID();
		final UUID runId = UUID.randomUUID();

		final Instant startsAt = Instant.parse("2024-01-01T10:00:00Z");
		final Instant snapshotSeenAt = Instant.parse("2024-01-01T09:00:00Z");

		// New StagedPlan signature includes snapshotSeenAt (the timestamp of the
		// snapshot/run),
		// so processing must use THAT (not Instant.now()) for monotonic correctness.
		final StagedPlan staged = new StagedPlan(stagedId, runId, "demo-provider", "p1", "Title", "online", startsAt,
				null, null, null, snapshotSeenAt, 0);

		when(stagingRepo.claimNextAnyProvider(eq(10), eq(3))).thenReturn(List.of(staged));

		final int processed = svc.processBatch(10, 3);

		assertEquals(1, processed);

		// Capture and assert the upsert content
		@SuppressWarnings("unchecked")
		final ArgumentCaptor<List<PlanUpsert>> upsertsCaptor = ArgumentCaptor.forClass(List.class);
		final ArgumentCaptor<Instant> seenAtCaptor = ArgumentCaptor.forClass(Instant.class);

		verify(planWriteRepo, times(1)).upsertAll(upsertsCaptor.capture(), seenAtCaptor.capture());

		final List<PlanUpsert> upserts = upsertsCaptor.getValue();
		assertNotNull(upserts);
		assertEquals(1, upserts.size());

		final PlanUpsert u = upserts.get(0);
		assertEquals("demo-provider", u.providerId());
		assertEquals("p1", u.externalPlanId());
		assertEquals("Title", u.title());
		assertEquals(startsAt, u.startsAt());
		assertNull(u.endsAt());
		assertNull(u.minPrice());
		assertNull(u.maxPrice());

		// "online" => everOnlineCurrent=true and lastSellMode=ONLINE
		assertTrue(u.everOnline());
		assertEquals(SellMode.ONLINE, u.lastSellMode());

		// seenAt must come from snapshotSeenAt (not "now-ish")
		assertEquals(snapshotSeenAt, seenAtCaptor.getValue());

		verify(stagingRepo, times(1)).markDone(stagedId);
		verify(stagingRepo, never()).markFailed(any(), anyString(), anyInt());
	}

	@Test
	void shouldMarkFailedWhenUpsertThrows() {
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);
		final PlanWriteRepository planWriteRepo = mock(PlanWriteRepository.class);

		final ProcessStagedPlansService svc = new ProcessStagedPlansService(stagingRepo, planWriteRepo, null);

		final UUID stagedId = UUID.randomUUID();
		final UUID runId = UUID.randomUUID();

		final StagedPlan staged = new StagedPlan(stagedId, runId, "demo-provider", "p1", "Title", "online",
				Instant.parse("2024-01-01T10:00:00Z"), null, null, null, Instant.parse("2024-01-01T09:00:00Z"), 0);

		when(stagingRepo.claimNextAnyProvider(eq(10), eq(2))).thenReturn(List.of(staged));
		doThrow(new RuntimeException("db down")).when(planWriteRepo).upsertAll(anyList(), any(Instant.class));

		final int processed = svc.processBatch(10, 2);

		assertEquals(1, processed);

		verify(stagingRepo, times(1)).markFailed(eq(stagedId), contains("db down"), eq(2));
		verify(stagingRepo, never()).markDone(any());
	}

	@Test
	void shouldReturn0WhenNoRowsClaimed() {
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);
		final PlanWriteRepository planWriteRepo = mock(PlanWriteRepository.class);

		final ProcessStagedPlansService svc = new ProcessStagedPlansService(stagingRepo, planWriteRepo, null);

		when(stagingRepo.claimNextAnyProvider(eq(10), eq(3))).thenReturn(List.of());

		final int processed = svc.processBatch(10, 3);

		assertEquals(0, processed);
		verifyNoInteractions(planWriteRepo);
		verify(stagingRepo, never()).markDone(any());
		verify(stagingRepo, never()).markFailed(any(), anyString(), anyInt());
	}

	@Test
	void shouldUseSafeDefaultsWhenArgsAreZeroOrNegative() {
		final StagingPlanPostgresRepository stagingRepo = mock(StagingPlanPostgresRepository.class);
		final PlanWriteRepository planWriteRepo = mock(PlanWriteRepository.class);

		final ProcessStagedPlansService svc = new ProcessStagedPlansService(stagingRepo, planWriteRepo, null);

		when(stagingRepo.claimNextAnyProvider(eq(1), eq(1))).thenReturn(List.of());

		final int processed = svc.processBatch(0, -5);

		assertEquals(0, processed);
		verify(stagingRepo, times(1)).claimNextAnyProvider(eq(1), eq(1));
		verifyNoInteractions(planWriteRepo);
	}
}
