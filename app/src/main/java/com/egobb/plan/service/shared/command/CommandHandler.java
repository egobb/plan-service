package com.egobb.plan.service.shared.command;

public interface CommandHandler<C extends Command, R> {
	R handle(C var1);
}
