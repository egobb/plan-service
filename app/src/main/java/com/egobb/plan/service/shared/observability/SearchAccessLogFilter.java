package com.egobb.plan.service.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Low-noise access log for GET /search.
 *
 * - Logs only slow requests or 5xx. - Avoids logging response payload. - Uses
 * MDC (request_id) for correlation.
 */
public class SearchAccessLogFilter extends OncePerRequestFilter {

	private static final Logger ACCESS_LOG = LoggerFactory.getLogger("access");
	private static final String PATH = "/search";

	private final long slowThresholdMs;

	public SearchAccessLogFilter(final long slowThresholdMs) {
		this.slowThresholdMs = Math.max(1L, slowThresholdMs);
	}

	@Override
	protected boolean shouldNotFilter(final HttpServletRequest request) {
		return !PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException {
		final long startNs = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} finally {
			final long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
			final int status = response.getStatus();
			final boolean is5xx = status >= 500;
			final boolean isSlow = durationMs >= this.slowThresholdMs;

			if (is5xx || isSlow) {
				final String startsAt = request.getParameter("starts_at");
				final String endsAt = request.getParameter("ends_at");
				final String startsAtAlt = request.getParameter("startsAt");
				final String endsAtAlt = request.getParameter("endsAt");

				final String s = (startsAt != null) ? startsAt : startsAtAlt;
				final String e = (endsAt != null) ? endsAt : endsAtAlt;

				if (is5xx) {
					ACCESS_LOG.error(
							"search_request status={} durationMs={} startsAt={} endsAt={} remoteAddr={} userAgent={}",
							status, durationMs, s, e, request.getRemoteAddr(), request.getHeader("User-Agent"));
				} else {
					ACCESS_LOG.warn(
							"search_request status={} durationMs={} startsAt={} endsAt={} remoteAddr={} userAgent={}",
							status, durationMs, s, e, request.getRemoteAddr(), request.getHeader("User-Agent"));
				}
			}
		}
	}
}
