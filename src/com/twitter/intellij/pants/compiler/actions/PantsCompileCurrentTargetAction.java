// Copyright 2017 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.compiler.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.twitter.intellij.pants.util.PantsUtil;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Stream;

/**
 * PantsCompileCurrentTargetAction is a UI action that compiles target(s) related to the file under edit.
 */
public class PantsCompileCurrentTargetAction extends PantsCurrentTarget {
  public PantsCompileCurrentTargetAction() {
    super("Compile target(s) in the selected editor", "compile");
  }

  @NotNull
  @Override
  public TaskExecutionResult operate(@NotNull project p, @NotNull PantsMakeBeforeRun runner) {
    runner.doCompile(p, this.getTargets(p).collect(Collectors.toSet()), false);
  }
}
