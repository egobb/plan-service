package com.egobb.plan.service.shared.command;

import com.egobb.plan.service.shared.annotation.ValidateParams;

@ValidateParams
public abstract class AbstractQueryHandler<Q extends Query, R> implements QueryHandler<Q, R> {
}
