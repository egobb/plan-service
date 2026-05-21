package com.egobb.plan.service.application.command.cmd;

import com.egobb.plan.service.shared.command.Command;

public record StageSnapshotCmd(String providerId, int batchSize) implements Command {
}
