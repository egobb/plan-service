package com.egobb.plan.service.infrastructure.persistence.postgres;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.application.search.PlanView;
import com.egobb.plan.service.domain.model.plan.SellMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanPostgresAdapterTest {

	@Mock
	private JdbcTemplate jdbcTemplate;

	@Mock
	private NamedParameterJdbcTemplate namedJdbcTemplate;

	private PlanPostgresAdapter adapter;

	@BeforeEach
	void setUp() {
		this.adapter = new PlanPostgresAdapter(this.jdbcTemplate);
		// Replace the internally-created NamedParameterJdbcTemplate with a mock
		ReflectionTestUtils.setField(this.adapter, "namedJdbcTemplate", this.namedJdbcTemplate);
	}

	@SuppressWarnings("unchecked")
	private static <T> RowMapper<T> anyRowMapper() {
		return (RowMapper<T>) any(RowMapper.class);
	}

	@Test
	void upsertAll_whenNullOrEmpty_doesNothing() {
		this.adapter.upsertAll(null, Instant.now());
		this.adapter.upsertAll(List.of(), Instant.now());

		verifyNoInteractions(this.jdbcTemplate);
		verifyNoInteractions(this.namedJdbcTemplate);
	}

	@Test
	void upsertAll_callsBatchUpdateWithExpectedBatchSize_andMapsFieldsCorrectly() throws Exception {
		final Instant seenAt = Instant.parse("2026-01-10T10:00:00Z");
		final Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
		final Instant endsAt = Instant.parse("2026-01-02T00:00:00Z");

		final PlanUpsert upsert = new PlanUpsert("provider-1", "P-1", "Title", startsAt, endsAt,
				new BigDecimal("10.00"), new BigDecimal("20.00"), true, SellMode.ONLINE);

		final List<PlanUpsert> upserts = List.of(upsert);

		@SuppressWarnings("unchecked")
		final ArgumentCaptor<ParameterizedPreparedStatementSetter<PlanUpsert>> setterCaptor = (ArgumentCaptor<ParameterizedPreparedStatementSetter<PlanUpsert>>) (ArgumentCaptor<?>) ArgumentCaptor
				.forClass(ParameterizedPreparedStatementSetter.class);

		this.adapter.upsertAll(upserts, seenAt);

		verify(this.jdbcTemplate).batchUpdate(
				argThat(sql -> sql.contains("INSERT INTO plans") && sql.contains("ON CONFLICT")), eq(upserts),
				eq(upserts.size()), setterCaptor.capture());

		final var setter = setterCaptor.getValue();
		assertNotNull(setter);

		final PreparedStatement ps = mock(PreparedStatement.class);

		// Execute mapping
		setter.setValues(ps, upsert);

		final InOrder inOrder = inOrder(ps);

		// 1 UUID
		inOrder.verify(ps).setObject(eq(1), any(UUID.class));

		// 2 providerId, 3 externalPlanId, 4 title
		inOrder.verify(ps).setString(2, "provider-1");
		inOrder.verify(ps).setString(3, "P-1");
		inOrder.verify(ps).setString(4, "Title");

		// 5 starts_at, 6 ends_at as Timestamp
		final ArgumentCaptor<Timestamp> startsTsCaptor = ArgumentCaptor.forClass(Timestamp.class);
		inOrder.verify(ps).setTimestamp(eq(5), startsTsCaptor.capture());
		assertEquals(Timestamp.from(startsAt), startsTsCaptor.getValue());

		final ArgumentCaptor<Timestamp> endsTsCaptor = ArgumentCaptor.forClass(Timestamp.class);
		inOrder.verify(ps).setTimestamp(eq(6), endsTsCaptor.capture());
		assertEquals(Timestamp.from(endsAt), endsTsCaptor.getValue());

		// 7 min_price, 8 max_price
		inOrder.verify(ps).setBigDecimal(7, new BigDecimal("10.00"));
		inOrder.verify(ps).setBigDecimal(8, new BigDecimal("20.00"));

		// 9 ever_online, 10 last_sell_mode
		inOrder.verify(ps).setBoolean(9, true);
		inOrder.verify(ps).setString(10, "online"); // SellMode.value()

		// 11 first_seen_at, 12 last_seen_at as Timestamp(seenAt)
		final Timestamp seenTs = Timestamp.from(seenAt);

		final ArgumentCaptor<Timestamp> firstSeenCaptor = ArgumentCaptor.forClass(Timestamp.class);
		inOrder.verify(ps).setTimestamp(eq(11), firstSeenCaptor.capture());
		assertEquals(seenTs, firstSeenCaptor.getValue());

		final ArgumentCaptor<Timestamp> lastSeenCaptor = ArgumentCaptor.forClass(Timestamp.class);
		inOrder.verify(ps).setTimestamp(eq(12), lastSeenCaptor.capture());
		assertEquals(seenTs, lastSeenCaptor.getValue());

		inOrder.verifyNoMoreInteractions();
	}

	@Test
	void upsertAll_whenEndsAtNull_setsNullTimestamp() throws Exception {
		final Instant seenAt = Instant.parse("2026-01-10T10:00:00Z");
		final Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");

		final PlanUpsert upsert = new PlanUpsert("provider-1", "P-2", "Title", startsAt, null, new BigDecimal("10.00"),
				new BigDecimal("20.00"), false, SellMode.OFFLINE);

		@SuppressWarnings("unchecked")
		final ArgumentCaptor<ParameterizedPreparedStatementSetter<PlanUpsert>> setterCaptor = (ArgumentCaptor<ParameterizedPreparedStatementSetter<PlanUpsert>>) (ArgumentCaptor<?>) ArgumentCaptor
				.forClass(ParameterizedPreparedStatementSetter.class);

		this.adapter.upsertAll(List.of(upsert), seenAt);

		verify(this.jdbcTemplate).batchUpdate(anyString(), anyList(), eq(1), setterCaptor.capture());

		final PreparedStatement ps = mock(PreparedStatement.class);
		setterCaptor.getValue().setValues(ps, upsert);

		verify(ps).setTimestamp(eq(6), isNull());
		verify(ps).setString(10, "offline");
	}

	@Test
	void search_withBothBounds_buildsSqlWithBothClauses_andClampsLimitOffset_andPassesParams() {
		when(this.namedJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), anyRowMapper()))
				.thenReturn(List.of());

		final var starts = Optional.of(Instant.parse("2026-01-01T00:00:00Z"));
		final var ends = Optional.of(Instant.parse("2026-01-31T00:00:00Z"));

		final int limit = 999;
		final int offset = -10;

		final List<PlanView> result = this.adapter.search(starts, ends, limit, offset);
		assertNotNull(result);

		final var sqlCaptor = ArgumentCaptor.forClass(String.class);
		final var paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

		verify(this.namedJdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyRowMapper());
		verifyNoMoreInteractions(this.namedJdbcTemplate);

		final String sql = sqlCaptor.getValue();
		final MapSqlParameterSource params = paramsCaptor.getValue();

		assertTrue(sql.contains("FROM plans"));
		assertTrue(sql.contains("AND ever_online = TRUE"));

		assertTrue(sql.contains("AND starts_at >= :startsAt"));
		assertTrue(sql.contains("AND ends_at IS NOT NULL AND ends_at <= :endsAt"));

		assertTrue(sql.contains("ORDER BY starts_at NULLS LAST, id"));
		assertTrue(sql.contains("LIMIT :limit OFFSET :offset"));

		assertEquals(Timestamp.from(starts.get()), params.getValue("startsAt"));
		assertEquals(Timestamp.from(ends.get()), params.getValue("endsAt"));

		assertEquals(500, params.getValue("limit"));
		assertEquals(0, params.getValue("offset"));
	}

	@Test
	void search_withoutBounds_buildsSqlWithoutOptionalClauses_andClampsLimitOffset() {
		when(this.namedJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), anyRowMapper()))
				.thenReturn(List.of());

		final List<PlanView> result = this.adapter.search(Optional.empty(), Optional.empty(), 0, -1);
		assertNotNull(result);

		final var sqlCaptor = ArgumentCaptor.forClass(String.class);
		final var paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);

		verify(this.namedJdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyRowMapper());
		verifyNoMoreInteractions(this.namedJdbcTemplate);

		final String sql = sqlCaptor.getValue();
		final MapSqlParameterSource params = paramsCaptor.getValue();

		assertTrue(sql.contains("AND ever_online = TRUE"));

		assertFalse(sql.contains("starts_at >= :startsAt"));
		assertFalse(sql.contains("ends_at <= :endsAt"));

		assertEquals(1, params.getValue("limit"));
		assertEquals(0, params.getValue("offset"));
	}

	@Test
	void search_withEndsBound_addsEndsAtClauseThatExcludesNullEndsAt() {
		when(this.namedJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), anyRowMapper()))
				.thenReturn(List.of());

		final var ends = Optional.of(Instant.parse("2026-01-31T00:00:00Z"));

		this.adapter.search(Optional.empty(), ends, 50, 0);

		final var sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(this.namedJdbcTemplate).query(sqlCaptor.capture(), any(MapSqlParameterSource.class), anyRowMapper());

		final String sql = sqlCaptor.getValue();
		assertTrue(sql.contains("ends_at IS NOT NULL AND ends_at <= :endsAt"));
	}

	@Test
	void search_clampsLimitAndOffsetBounds() {
		when(this.namedJdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), anyRowMapper()))
				.thenReturn(List.of());

		this.adapter.search(Optional.empty(), Optional.empty(), -999, -999);

		final var paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
		verify(this.namedJdbcTemplate).query(anyString(), paramsCaptor.capture(), anyRowMapper());

		final MapSqlParameterSource params = paramsCaptor.getValue();
		assertEquals(1, params.getValue("limit"));
		assertEquals(0, params.getValue("offset"));
	}
}
