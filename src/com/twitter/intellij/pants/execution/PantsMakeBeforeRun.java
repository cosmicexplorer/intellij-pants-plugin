// Copyright 2016 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.execution;

import com.intellij.execution.BeforeRunTask;
import com.intellij.execution.CommonProgramRunConfigurationParameters;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfileWithCompileBeforeLaunchOption;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.execution.impl.RunManagerImpl;
import com.intellij.execution.process.CapturingAnsiEscapesAwareProcessHandler;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.twitter.intellij.pants.PantsBundle;
import com.twitter.intellij.pants.file.FileChangeTracker;
import com.twitter.intellij.pants.metrics.PantsExternalMetricsListenerManager;
import com.twitter.intellij.pants.model.PantsOptions;
import com.twitter.intellij.pants.settings.PantsSettings;
import com.twitter.intellij.pants.ui.PantsConsoleManager;
import com.twitter.intellij.pants.util.PantsConstants;
import com.twitter.intellij.pants.util.PantsUtil;
import icons.PantsIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.scala.testingSupport.test.AbstractTestRunConfiguration;

import javax.swing.Icon;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * PantsMakeBeforeRun creates a custom Make process `PantsCompile` to replace IntelliJ's default Make process whenever a new configuration
 * is added under a pants project via {@link PantsMakeBeforeRun#replaceDefaultMakeWithPantsMake}, so the time to launch pants is minimized.
 * <p/>
 * Motivation: By default, IntelliJ's Make process is invoked before any JUnit/Scala/Application run which has unnecessary (for Pants)
 * long steps to scan the entire project to assist external builders' incremental compile.
 */
public class PantsMakeBeforeRun extends ExternalSystemBeforeRunTaskProvider {

  public static final Key<ExternalSystemBeforeRunTask> ID = Key.create("Pants.BeforeRunTask");
  public static final String ERROR_TAG = "[error]";
  private static ConcurrentHashMap<Project, Process> runningPantsProcesses = new ConcurrentHashMap<>();

  public static boolean hasActivePantsProcess(@NotNull Project project) {
    return runningPantsProcesses.containsKey(project);
  }

  public PantsMakeBeforeRun(@NotNull Project project) {
    super(PantsConstants.SYSTEM_ID, project, ID);
  }

  public static void replaceDefaultMakeWithPantsMake(@NotNull Project project, @NotNull RunnerAndConfigurationSettings settings) {
    RunManager runManager = RunManager.getInstance(project);
    if (!(runManager instanceof RunManagerImpl)) {
      return;
    }
    RunManagerImpl runManagerImpl = (RunManagerImpl) runManager;
    RunConfiguration runConfiguration = settings.getConfiguration();

    Optional<VirtualFile> buildRoot = PantsUtil.findBuildRoot(project);

    /**
     /**
     * Scala related run/test configuration inherit {@link AbstractTestRunConfiguration}
     */
    if (runConfiguration instanceof AbstractTestRunConfiguration) {
      if (buildRoot.isPresent()) {
        ((AbstractTestRunConfiguration) runConfiguration).setWorkingDirectory(buildRoot.get().getPath());
      }
    }
    /**
     * JUnit, Application, etc configuration inherit {@link CommonProgramRunConfigurationParameters}
     */
    else if (runConfiguration instanceof CommonProgramRunConfigurationParameters) {
      if (buildRoot.isPresent()) {
        ((CommonProgramRunConfigurationParameters) runConfiguration).setWorkingDirectory(buildRoot.get().getPath());
      }
    }
    /**
     * If neither applies (e.g. Pants or pytest configuration), do not continue.
     */
    else {
      return;
    }

    /**
     * Every time a new configuration is created, 'Make' is by default added to the "Before launch" tasks.
     * Therefore we want to overwrite it with {@link PantsMakeBeforeRun}.
     */
    BeforeRunTask pantsMakeTask = new ExternalSystemBeforeRunTask(ID, PantsConstants.SYSTEM_ID);
    pantsMakeTask.setEnabled(true);
    runManagerImpl.setBeforeRunTasks(runConfiguration, Collections.singletonList(pantsMakeTask), false);
  }

  public static void terminatePantsProcess(Project project) {
    Process process = runningPantsProcesses.get(project);
    if (process != null) {
      process.destroy();
      runningPantsProcesses.remove(project, process);
    }
  }

  @Override
  public String getName() {
    return "PantsCompile";
  }

  @Override
  public String getDescription(ExternalSystemBeforeRunTask task) {
    return "PantsCompile";
  }

  @NotNull
  @Override
  public ExternalSystemBeforeRunTask createTask(@NotNull RunConfiguration runConfiguration) {
    return new ExternalSystemBeforeRunTask(ID, PantsConstants.SYSTEM_ID);
  }

  @Nullable
  @Override
  public Icon getIcon() {
    return PantsIcons.Icon;
  }

  @Override
  public boolean canExecuteTask(
    RunConfiguration configuration, ExternalSystemBeforeRunTask beforeRunTask
  ) {
    return true;
  }

  // compile as default task
  @Override
  public boolean executeTask(
    final DataContext context,
    RunConfiguration configuration,
    ExecutionEnvironment env,
    ExternalSystemBeforeRunTask beforeRunTask
  ) {
    Project currentProject = configuration.getProject();
    prepareIDE(currentProject);
    Set<String> targetAddressesToCompile = PantsUtil.filterGenTargets(getTargetAddressesToCompile(configuration));
    TaskExecutionResult result = doCompile(currentProject, targetAddressesToCompile, false);
    return result.success;
  }

  public static class TaskExecutionResult {
    public final boolean success;
    public final Optional<String> output;
    public TaskExecutionResult(boolean success, Optional<String> output) {
      this.success = success;
      this.output = output;
    }
  }

  public TaskExecutionResult executeTask(@NotNull Module[] modules, String target) {
    if (modules.length == 0) {
      return new TaskExecutionResult();
    }
    return executeTask(modules[0].getProject(), target, new ArrayList<String>(), getTargetAddressesToCompile(modules));
  }

  public TaskExecutionResult executeTask(Project currentProject, String target) {
    Set<String> addresses = getTargetAddressesToCompile(ModuleManager.getInstance(project).getModules());
    return executeTask(currentProject, target, addresses);
  }

  public TaskExecutionResult executeTask(Project currentProject, String target, Set<String> addresses) {
    return executeTask(currentProject, target, new ArrayList<String>(), addresses);
  }

  /**
   * @param currentProject:  current project
   * @param target:          pants action to perform, e.g. "compile"
   * @param description:     human-readable description of action
   * @param preTargetArgs:   arguments to pants command inserted before the action to perform
   * @param targetAddresses: set of target addresses to compile
   * @return whether the execution is successful, additional message along with the execution
   * in a Pair object.
   */
  public TaskExecutionResult executeTask(
      Project currentProject, String target, String description, List<String> preTargetArgs, Set<String> targetAddresses) {
    if (currentProject == null) {
      notify(description, "project not found", NotificationType.ERROR);
      return new TaskExecutionResult(false, Optional.empty());
    }

    prepareIDE(currentProject);
    if (targetAddresses.isEmpty()) {
      showPantsMakeTaskMessage("No target found in configuration.\n", ConsoleViewContentType.SYSTEM_OUTPUT, currentProject);
      return new TaskExecutionResult(true, Optional.empty());
    }

    Optional<VirtualFile> findPantsResult = PantsUtil.findPantsExecutable(currentProject);
    if (!findPantsResult.isPresent()) {
      return new TaskExecutionResult(
        false,
        Optional.of(
          PantsBundle.message("pants.error.no.pants.executable.by.path", currentProject.getProjectFilePath())))
    }
    VirtualFile pantsExecutable = findPantsResult.get();

    final GeneralCommandLine commandLine = PantsUtil.defaultCommandLine(pantsExecutable.getPath());

    showPantsMakeTaskMessage("Checking Pants options...\n", ConsoleViewContentType.SYSTEM_OUTPUT, currentProject);
    Optional<PantsOptions> pantsOptional = PantsOptions.getPantsOptions(currentProject);
    if (!pantsOptional.isPresent()) {
      showPantsMakeTaskMessage("Pants Options not found.\n", ConsoleViewContentType.ERROR_OUTPUT, currentProject);
      return Pair.create(false, Optional.empty());
    }

    PantsOptions pantsOptions = pantsOptional.get();

    /* Global options section. */
    commandLine.addParameter(PantsConstants.PANTS_CLI_OPTION_NO_COLORS);

    PantsSettings settings = PantsSettings.getInstance(currentProject);
    if (settings.isUseIdeaProjectJdk()) {
      try {
        commandLine.addParameter(PantsUtil.getJvmDistributionPathParameter(PantsUtil.getJdkPathFromIntelliJCore()));
      }
      catch (Exception e) {
        showPantsMakeTaskMessage(e.getMessage(), ConsoleViewContentType.ERROR_OUTPUT, currentProject);
        return Pair.create(false, Optional.empty());
      }
    }
    // add provided parameters before
    commandLine.addParameters(preTargetArgs);

    /* Goals and targets section. */
    commandLine.addParameter(target);
    commandLine.addParameters(targetAddresses);

    final Process process;
    try {
      process = commandLine.createProcess();
    }
    catch (ExecutionException e) {
      showPantsMakeTaskMessage(e.getMessage(), ConsoleViewContentType.ERROR_OUTPUT, currentProject);
      return Pair.create(false, Optional.empty());
    }

    final CapturingProcessHandler processHandler = new CapturingAnsiEscapesAwareProcessHandler(process, commandLine.getCommandLineString());
    final List<String> output = new ArrayList<>();
    processHandler.addProcessListener(new ProcessAdapter() {
      @Override
      public void onTextAvailable(ProcessEvent event, Key outputType) {
        super.onTextAvailable(event, outputType);
        showPantsMakeTaskMessage(event.getText(), ConsoleViewContentType.NORMAL_OUTPUT, currentProject);
        output.add(event.getText());
      }
    });

    runningPantsProcesses.put(currentProject, process);
    processHandler.runProcess();
    runningPantsProcesses.remove(currentProject, process);

    final boolean success = (process.exitValue() == 0);
    if (success) {
      notify(description, "succeeded", NotificationType.INFO);
    } else {
      notify(description, "failed", NotificationType.ERROR);
    }

    String finalOutString = String.join("", output);
    return new TaskExecutionResult(success, Optional.of(finalOutString));
  }

  public TaskExecutionResult doCompile(Project currentProject, Set<String> targetAddressesToCompile, boolean useCleanAll) {
    // If project has not changed since last Compile, return immediately.
    if (!FileChangeTracker.shouldRecompileThenReset(currentProject, targetAddressesToCompile)) {
      PantsExternalMetricsListenerManager.getInstance().logIsPantsNoopCompile(true);
      Notification start = new Notification(PantsConstants.PANTS, "Pants Compile", "Already up to date.\n", NotificationType.INFORMATION);
      Notifications.Bus.notify(start);
      return Pair.create(true, Optional.of(PantsConstants.NOOP_COMPILE));
    }

    List<String> args = new ArrayList<String>();
    if (useCleanAll) {
      args.add("clean-all");
      if (pantsOptions.supportsAsyncCleanAll()) {
        args.add(PantsConstants.PANTS_CLI_OPTION_ASYNC_CLEAN_ALL);
      }
    }
    if (pantsOptions.supportsManifestJar()) {
      args.add(PantsConstants.PANTS_CLI_OPTION_EXPORT_CLASSPATH_MANIFEST_JAR);
    }
    args.add("export-classpath");

    TaskExecutionResult result = executeTask(currentProject, "compile", "compile", args, targetAddressesToCompile);

    if (result.success) {
      FileChangeTracker.addManifestJarIntoSnapshot(currentProject);
    } else {
      // Mark project dirty if compile failed.
      FileChangeTracker.markDirty(currentProject);
    }

    // Sync files as generated sources may have changed after Pants compile.
    PantsUtil.synchronizeFiles();

    return result;
  }

  private void notify(final String title, final String subtitle, final NotificationType type) {
  /* Show pop up notification about pants compile result. */
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        Notification notif = new Notification(PantsConstants.PANTS, PantsIcons.Icon, title, subtitle, type);
        Notifications.Bus.notify(notif);
        // String message = String.format("Pants %s %s", description, (success ? "succeeded" : "failed"));
        // NotificationType type = success ? NotificationType.INFORMATION : NotificationType.ERROR;
        // Notification start = new Notification(PantsConstants.PANTS, PantsIcons.Icon, "Compile message", message, type);
      }
    });
  }

  private void prepareIDE(Project project) {
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        /* Clear message window. */
        ConsoleView executionConsole = PantsConsoleManager.getOrMakeNewConsole(project);
        executionConsole.getComponent().setVisible(true);
        executionConsole.clear();
        ToolWindowManagerEx.getInstance(project).getToolWindow(PantsConstants.PANTS_CONSOLE_NAME).activate(null);
        /* Force cached changes to disk. */
        FileDocumentManager.getInstance().saveAllDocuments();
        project.save();
      }
    }, ModalityState.NON_MODAL);
  }

  @NotNull
  protected Set<String> getTargetAddressesToCompile(RunConfiguration configuration) {
    /* Scala run configurations */
    if (configuration instanceof AbstractTestRunConfiguration) {
      Module module = ((AbstractTestRunConfiguration) configuration).getModule();
      return getTargetAddressesToCompile(new Module[]{module});
    }
    /* JUnit, Application run configurations */
    else if (configuration instanceof RunProfileWithCompileBeforeLaunchOption) {
      RunProfileWithCompileBeforeLaunchOption config = (RunProfileWithCompileBeforeLaunchOption) configuration;
      Module[] targetModules = config.getModules();
      return getTargetAddressesToCompile(targetModules);
    }
    else {
      return Collections.emptySet();
    }
  }

  @NotNull
  private Set<String> getTargetAddressesToCompile(Module[] targetModules) {
    if (targetModules.length == 0) {
      return Collections.emptySet();
    }
    Set<String> result = new HashSet<>();
    for (Module targetModule : targetModules) {
      result.addAll(PantsUtil.getNonGenTargetAddresses(targetModule));
    }
    return result;
  }


  private void showPantsMakeTaskMessage(String message, ConsoleViewContentType type, Project project) {
    ConsoleView executionConsole = PantsConsoleManager.getOrMakeNewConsole(project);
    // Create a filter that monitors console outputs, and turns them into a hyperlink if applicable.
    Filter filter = new Filter() {
      @Nullable
      @Override
      public Result applyFilter(String line, int entireLength) {
        Optional<ParseResult> result = ParseResult.parseErrorLocation(line, ERROR_TAG);
        if (result.isPresent()) {

          OpenFileHyperlinkInfo linkInfo = new OpenFileHyperlinkInfo(
            project,
            result.get().getFile(),
            result.get().getLineNumber() - 1, // line number needs to be 0 indexed
            result.get().getColumnNumber() - 1 // column number needs to be 0 indexed
          );
          int startHyperlink = entireLength - line.length() + line.indexOf(ERROR_TAG);

          return new Result(
            startHyperlink,
            entireLength,
            linkInfo,
            null // TextAttributes, going with default hence null
          );
        }
        return null;
      }
    };

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        executionConsole.addMessageFilter(filter);
        executionConsole.print(message, type);
      }
    }, ModalityState.NON_MODAL);
  }

  /**
   * Encapsulate the result of parsed data.
   */
  static class ParseResult {
    private VirtualFile file;
    private int lineNumber;
    private int columnNumber;


    /**
     * This function parses Pants output against known file and tag,
     * and returns (file, line number, column number)
     * encapsulated in `ParseResult` object if the output contains valid information.
     *
     * @param line original Pants output
     * @param tag  known tag. e.g. [error]
     * @return `ParseResult` instance
     */
    public static Optional<ParseResult> parseErrorLocation(String line, String tag) {
      if (!line.contains(tag)) {
        return Optional.empty();
      }

      String[] splitByColon = line.split(":");
      if (splitByColon.length < 3) {
        return Optional.empty();
      }

      try {
        // filePath path is between tag and first colon
        String filePath = splitByColon[0].substring(splitByColon[0].indexOf(tag) + tag.length()).trim();
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
        if (virtualFile == null) {
          return Optional.empty();
        }
        // line number is between first and second colon
        int lineNumber = Integer.valueOf(splitByColon[1]);
        // column number is between second and third colon
        int columnNumber = Integer.valueOf(splitByColon[2]);
        return Optional.of(new ParseResult(virtualFile, lineNumber, columnNumber));
      }
      catch (NumberFormatException e) {
        return Optional.empty();
      }
    }

    private ParseResult(VirtualFile file, int lineNumber, int columnNumber) {
      this.file = file;
      this.lineNumber = lineNumber;
      this.columnNumber = columnNumber;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (getClass() != obj.getClass()) {
        return false;
      }
      ParseResult other = (ParseResult) obj;
      return Objects.equals(file, other.file)
             && Objects.equals(lineNumber, other.lineNumber)
             && Objects.equals(columnNumber, other.columnNumber);
    }

    public VirtualFile getFile() {
      return file;
    }

    public int getLineNumber() {
      return lineNumber;
    }

    public int getColumnNumber() {
      return columnNumber;
    }
  }
}
