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
 * PantsCompileAllTargetsInModuleAction is a UI action that compiles target(s) related to the file under edit.
 */
public abstract class AllTargetsInModule extends ActionBase {

  public AllTargetsInModule(@NotNull String name, @NotNull String description) {
    super(name, description);
  }

  /**
   * Find the target(s) that are only associated with the file opened in the selected editor.
   */
  @NotNull
  @Override
  public Stream<String> getTargets(@NotNull Project project) {
    return Arrays.stream(ModuleManager.getInstance(project).getModules())
      .map(PantsUtil::getNonGenTargetAddresses)
      .flatMap(Collection::stream);
  }
}
