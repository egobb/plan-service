package com.egobb.plan.service.infrastructure.persistence.postgres;

import com.egobb.plan.service.application.ingest.PlanUpsert;
import com.egobb.plan.service.application.search.PlanView;
import com.egobb.plan.service.domain.port.PlanReadRepository;
import com.egobb.plan.service.domain.port.PlanWriteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlanPostgresAdapter implements PlanReadRepository, PlanWriteRepository {

	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedJdbcTemplate;

	public PlanPostgresAdapter(final JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
	}

	@Override
	public void upsertAll(final List<PlanUpsert> upserts, final Instant seenAt) {
		if (upserts == null || upserts.isEmpty())
			return;

		final Timestamp seenTs = Timestamp.from(seenAt);

		this.jdbcTemplate.batchUpdate(
				"""
						INSERT INTO plans (
						  id, provider_id, external_plan_id, title,
						  starts_at, ends_at, min_price, max_price,
						  ever_online, last_sell_mode,
						  first_seen_at, last_seen_at
						)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
						ON CONFLICT (provider_id, external_plan_id) DO UPDATE SET
						  -- Monotonic updates: avoid old snapshots overriding newer data.
						  title = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.title ELSE plans.title END,
						  starts_at = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.starts_at ELSE plans.starts_at END,
						  ends_at = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.ends_at ELSE plans.ends_at END,
						  min_price = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.min_price ELSE plans.min_price END,
						  max_price = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.max_price ELSE plans.max_price END,
						  ever_online = (plans.ever_online OR EXCLUDED.ever_online),
						  last_sell_mode = CASE WHEN EXCLUDED.last_seen_at >= plans.last_seen_at THEN EXCLUDED.last_sell_mode ELSE plans.last_sell_mode END,
						  last_seen_at = GREATEST(plans.last_seen_at, EXCLUDED.last_seen_at)
						""",
				upserts, upserts.size(), (ps, u) -> {

					ps.setObject(1, UUID.randomUUID());
					ps.setString(2, u.providerId());
					ps.setString(3, u.externalPlanId());
					ps.setString(4, u.title());

					// Instants -> Timestamp
					ps.setTimestamp(5, toTs(u.startsAt()));
					ps.setTimestamp(6, toTs(u.endsAt()));

					ps.setBigDecimal(7, u.minPrice());
					ps.setBigDecimal(8, u.maxPrice());

					ps.setBoolean(9, u.everOnline());
					ps.setString(10, u.lastSellMode().value());

					ps.setTimestamp(11, seenTs);
					ps.setTimestamp(12, seenTs);
				});
	}

	private static Timestamp toTs(final Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	@Override
	public List<PlanView> search(final Optional<Instant> startsAt, final Optional<Instant> endsAt, final int limit,
			final int offset) {

		final StringBuilder sql = new StringBuilder("""
				SELECT id, title, starts_at, ends_at, min_price, max_price
				FROM plans
				WHERE 1=1
				  AND ever_online = TRUE
				""");

		final MapSqlParameterSource params = new MapSqlParameterSource();

		startsAt.map(Timestamp::from).ifPresent(ts -> {
			sql.append(" AND starts_at >= :startsAt");
			params.addValue("startsAt", ts);
		});

		endsAt.map(Timestamp::from).ifPresent(ts -> {
			sql.append(" AND ends_at IS NOT NULL AND ends_at <= :endsAt");
			params.addValue("endsAt", ts);
		});

		final int safeLimit = Math.max(1, Math.min(limit, 500));
		final int safeOffset = Math.max(0, offset);

		sql.append(" ORDER BY starts_at NULLS LAST, id");
		sql.append(" LIMIT :limit OFFSET :offset");

		params.addValue("limit", safeLimit);
		params.addValue("offset", safeOffset);

		return this.namedJdbcTemplate.query(sql.toString(), params, PLAN_VIEW_MAPPER);
	}

	private static final RowMapper<PlanView> PLAN_VIEW_MAPPER = (rs, rowNum) -> new PlanView((UUID) rs.getObject("id"),
			rs.getString("title"), tsToInstant(rs.getTimestamp("starts_at")), tsToInstant(rs.getTimestamp("ends_at")),
			rs.getBigDecimal("min_price"), rs.getBigDecimal("max_price"));

	private static Instant tsToInstant(final Timestamp ts) {
		return ts == null ? null : ts.toInstant();
	}
}
