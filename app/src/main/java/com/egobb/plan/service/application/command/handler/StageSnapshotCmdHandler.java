package com.egobb.plan.service.application.command.handler;

import com.egobb.plan.service.application.command.cmd.StageSnapshotCmd;
import com.egobb.plan.service.domain.service.StageSnapshotService;
import com.egobb.plan.service.shared.command.CommandHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StageSnapshotCmdHandler implements CommandHandler<StageSnapshotCmd, StageSnapshotService.StageResult> {

	private final StageSnapshotService stageSnapshotService;

	@Override
	public StageSnapshotService.StageResult handle(final StageSnapshotCmd command) {
		return this.stageSnapshotService.stage(command.providerId(), command.batchSize());
	}
}
