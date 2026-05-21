package com.egobb.plan.service.shared.command;

public interface QueryHandler<Q extends Query, R> {
	R handle(Q var1);
}
