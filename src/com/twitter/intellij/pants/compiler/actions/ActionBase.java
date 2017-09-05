// Copyright 2017 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.compiler.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.twitter.intellij.pants.execution.PantsMakeBeforeRun;
import com.twitter.intellij.pants.util.PantsConstants;
import icons.PantsIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PantsCompileCurrentTargetAction is a UI action that compiles target(s) related to the file under edit.
 */
public abstract class ActionBase extends AnAction implements DumbAware {
  @NotNull
  public final String description;

  // "description" is used in notification produced if no project exists; can be same as name
  public ActionBase(@NotNull String name, @NotNull String description) {
    super(name);
    this.description = description;
  }

  @NotNull
  public abstract Stream<String> getTargets(@NotNull Project project);

  @NotNull
  public abstract PantsMakeBeforeRun.TaskExecutionResult operate(
      @NotNull Project p, @NotNull PantsMakeBeforeRun runner, Set<String> targets);

  @Override
  public void actionPerformed(@Nullable AnActionEvent e) {
    if (e == null) {
      return;
    }

    Project project = e.getProject();

    if (project == null) {
      Notification notification = new Notification(
        PantsConstants.PANTS,
        PantsIcons.Icon,
        "Project not found",
        String.format("%s failed", this.description),
        null,
        NotificationType.ERROR,
        null
      );
      Notifications.Bus.notify(notification);
      return;
    }

    Set<String> fullTargets = getTargets(project).collect(Collectors.toSet());
    final PantsMakeBeforeRun runner = (PantsMakeBeforeRun) ExternalSystemBeforeRunTaskProvider.getProvider(project, PantsMakeBeforeRun.ID);
    ApplicationManager.getApplication().executeOnPooledThread(() -> operate(project, runner));
  }
}
