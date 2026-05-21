package com.egobb.plan.service.infrastructure.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

/**
 * Very small distributed lock based on Postgres advisory locks.
 * <p>
 * IMPORTANT: advisory locks are tied to a DB session. To release them reliably
 * in a pooled setup, we keep the underlying JDBC connection open for the whole
 * "critical section".
 */
@Component
@Slf4j
public class PostgresAdvisoryLockService {

	private final DataSource dataSource;

	public PostgresAdvisoryLockService(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public Optional<LockHandle> tryLock(String lockName) {
		final long key = fnv1a64(lockName);
		Connection conn = null;
		try {
			conn = this.dataSource.getConnection();
			conn.setAutoCommit(true);

			try (final PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
				ps.setLong(1, key);
				try (final ResultSet rs = ps.executeQuery()) {
					rs.next();
					final boolean acquired = rs.getBoolean(1);
					if (!acquired) {
						conn.close();
						return Optional.empty();
					}
					return Optional.of(new LockHandle(conn, key, lockName));
				}
			}
		} catch (final Exception e) {
			if (conn != null) {
				try {
					conn.close();
				} catch (final Exception ignored) {
					// ignore
				}
			}
			log.warn("Failed to acquire advisory lock '{}': {}", lockName, e.toString());
			return Optional.empty();
		}
	}

	public static final class LockHandle implements AutoCloseable {
		private final Connection connection;
		private final long key;
		private final String lockName;
		private boolean closed;

		private LockHandle(Connection connection, long key, String lockName) {
			this.connection = connection;
			this.key = key;
			this.lockName = lockName;
		}

		@Override
		public void close() {
			if (this.closed) {
				return;
			}
			this.closed = true;
			try (final PreparedStatement ps = this.connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				ps.setLong(1, this.key);
				ps.execute();
			} catch (final Exception e) {
				log.warn("Failed to release advisory lock '{}': {}", this.lockName, e.toString());
			} finally {
				try {
					this.connection.close();
				} catch (final Exception ignored) {
					// ignore
				}
			}
		}
	}

	/**
	 * Stable 64-bit hash for lock names.
	 * <p>
	 * We avoid String.hashCode() because it is 32-bit and easier to collide.
	 */
	static long fnv1a64(String s) {
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < s.length(); i++) {
			hash ^= s.charAt(i);
			hash *= 0x100000001b3L;
		}
		return hash;
	}
}
