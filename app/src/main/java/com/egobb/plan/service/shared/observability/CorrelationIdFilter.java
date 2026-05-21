package com.egobb.plan.service.shared.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds a low-cardinality request correlation id: - Reads X-Request-Id if
 * present - Otherwise generates a UUID - Propagates it back in the response
 * header - Stores it in MDC under request_id
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";

	@Override
	protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
			final FilterChain filterChain) throws ServletException, IOException {
		final String incoming = request.getHeader(HEADER_NAME);
		final String requestId = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;

		final boolean set = putIfAbsent(MdcKeys.REQUEST_ID, requestId);
		response.setHeader(HEADER_NAME, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			if (set) {
				MDC.remove(MdcKeys.REQUEST_ID);
			}
		}
	}

	private static boolean putIfAbsent(final String key, final String value) {
		if (value == null) {
			return false;
		}
		if (MDC.get(key) != null) {
			return false;
		}
		MDC.put(key, value);
		return true;
	}
}
