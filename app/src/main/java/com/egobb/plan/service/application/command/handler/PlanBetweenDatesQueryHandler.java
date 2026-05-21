package com.egobb.plan.service.application.command.handler;

import com.egobb.plan.service.application.command.query.PlanBetweenDatesQuery;
import com.egobb.plan.service.application.search.PlanView;
import com.egobb.plan.service.domain.port.PlanReadRepository;
import com.egobb.plan.service.shared.command.AbstractQueryHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanBetweenDatesQueryHandler extends AbstractQueryHandler<PlanBetweenDatesQuery, List<PlanView>> {

	private final PlanReadRepository planReadRepository;

	@Override
	@Cacheable(cacheNames = "searchHotRanges", key = "#p0 != null ? #p0.toString() : 'null'", condition = "#p0 != null && #p0.getOffset() == 0 && #p0.getLimit() <= 100", unless = "#result == null || #result.isEmpty()")
	public List<PlanView> handle(final PlanBetweenDatesQuery query) {
		return this.planReadRepository.search(query.getStartsAt(), query.getEndsAt(), query.getLimit(),
				query.getOffset());
	}
}
