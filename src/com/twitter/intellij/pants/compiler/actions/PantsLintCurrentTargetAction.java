// Copyright 2017 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.compiler.actions;

import com.intellij.openapi.project.Project;
import com.twitter.intellij.pants.execution.PantsMakeBeforeRun;
import org.jetbrains.annotations.NotNull;

/**
 * PantsLintCurrentTargetAction is a UI action that lints the file under edit.
 */
public class PantsLintCurrentTargetAction extends CurrentTarget {
  public PantsLintCurrentTargetAction() {
    super("Compile target(s) in the selected editor", "lint");
  }

  @NotNull
  @Override
  public PantsMakeBeforeRun.TaskExecutionResult operate(@NotNull Project p, @NotNull PantsMakeBeforeRun runner) {
    runner.executeTask(p, "lint", "lint");
  }

  @Override 
}
