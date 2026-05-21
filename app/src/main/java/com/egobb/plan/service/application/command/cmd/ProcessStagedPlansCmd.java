package com.egobb.plan.service.application.command.cmd;

import com.egobb.plan.service.shared.command.Command;

public record ProcessStagedPlansCmd(int batchSize, int maxAttempts) implements Command {
}
