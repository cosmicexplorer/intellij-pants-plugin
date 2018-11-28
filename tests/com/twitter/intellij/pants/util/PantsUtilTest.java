// Copyright 2017 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.util;

import com.google.common.collect.Lists;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.twitter.intellij.pants.PantsException;
import com.twitter.intellij.pants.testFramework.OSSPantsImportIntegrationTest;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PantsUtilTest extends OSSPantsImportIntegrationTest {

  protected static final Logger LOG = Logger.getInstance(PantsUtilTest.class);

  public void testIsPantsProjectFile() {
    // Current project path should be under a Pants repo.
    assertTrue(PantsUtil.isPantsProjectFile(LocalFileSystem.getInstance().findFileByPath(getProjectPath())));
    // File system root should not.
    assertFalse(PantsUtil.isPantsProjectFile(LocalFileSystem.getInstance().findFileByPath("/")));
  }

  protected List<Sdk> getSameJdks(Sdk sdk) {
    return getAllJdks()
      .filter(jdk -> jdk == sdk)
      .collect(Collectors.toList());
  }

  public void testFindJdk() {
    final File executable = PantsUtil.findPantsExecutable(getProjectFolder()).get();
    assertEquals(Lists.newArrayList(), getAllJdks().collect(Collectors.toList()));

    final Sdk sdkA = getDefaultJavaSdk(executable.getPath()).get();
    assertEquals(Lists.newArrayList(sdkA), getSameJdks(sdkA));

    final List<Sdk> singleSdkInTable = getSameJdks(sdkA);
    assertTrue(singleSdkInTable.get(0).getName().contains("pants"));

    final List<Sdk> twoEntriesSameSdk = Lists.newArrayList(sdkA, sdkA);
    // manually adding the same jdk to the table should result in two identical
    // entries
    ApplicationManager.getApplication().runWriteAction(() -> {
        // no need to use disposable here, because this should not add a new jdk
        ProjectJdkTable.getInstance().addJdk(sdkA);
    });
    assertEquals(twoEntriesSameSdk, getSameJdks(sdkA));

    // calling getDefaultJavaSdk should only add a new entry to the table if it
    // needs to make one
    final Sdk sdkB = getDefaultJavaSdk(executable.getPath()).get();
    // Make sure they are identical, meaning that no new JDK was created on the 2nd find.
    assertTrue(sdkA == sdkB);
    assertEquals(twoEntriesSameSdk, getSameJdks(sdkA));
  }

  protected String getPantsExecutablePath() {
    return PantsUtil.findPantsExecutable(getParentPath()).get().getPath();
  }

  public void testNonUtf8LocaleSubprocessFails() throws ExecutionException {
    final GeneralCommandLine command = PantsUtil.defaultCommandLine(getPantsExecutablePath());
    command.addParameter("options");

    final Map<String, String> localeOverrides = new HashMap<>();
    final String badLocale = "en_US.US-ASCII";
    localeOverrides.put("LANG", badLocale);
    localeOverrides.put("LC_ALL", badLocale);

    final Map<String, String> cmdEnv = command.getEnvironment();
    cmdEnv.putAll(localeOverrides);

    final ProcessOutput processOutput = PantsUtil.getCmdOutput(command, null);
    assertFalse(processOutput.checkSuccess(LOG));

    final String stderrString = processOutput.getStderr();
    assertContainsSubstring(stderrString,
                            "pants.bin.pants_loader.InvalidLocaleError: System preferred encoding is `US-ASCII`, but `UTF-8` is required.");
  }

  public void testSubprocessWithShellEnvSucceeds() throws ExecutionException {
    final GeneralCommandLine command = PantsUtil.defaultCommandLine(getPantsExecutablePath());
    command.addParameter("options");

    final ProcessOutput processOutput = PantsUtil.getCmdOutput(command, null);
    assertTrue(processOutput.checkSuccess(LOG));
  }

  public void testisBUILDFilePath() {
    assertFalse("pants.ini file should not be interpreted as a BUILD file",
                PantsUtil.isBUILDFilePath(pantsIniFilePath));

    assertFalse("made up file path should not be interpreted as a BUILD file",
                PantsUtil.isBUILDFilePath(nonexistentFilePath));

    assertTrue("made up BUILD file path should be interpreted as a BUILD file path",
               PantsUtil.isBUILDFilePath(nonexistentBuildFilePath));

    assertTrue("path to invalid, existing BUILD file should be interpreted as a BUILD file path",
               PantsUtil.isBUILDFilePath(invalidBuildFilePath));
  }

  public void testListAllTargets() {
    assertEquals("pants.ini file should have no targets",
                 PantsUtil.listAllTargets(pantsIniFilePath),
                 Lists.newArrayList());

    assertEquals("made up non-BUILD file path should have no targets",
                 PantsUtil.listAllTargets(nonexistentFilePath),
                 Lists.newArrayList());

    try {
      PantsUtil.listAllTargets(nonexistentBuildFilePath);
      fail(String.format("%s should have been thrown", PantsException.class));
    } catch (PantsException ignored) {
    }

    try {
      PantsUtil.listAllTargets(invalidBuildFilePath);
      fail(String.format("%s should have been thrown", PantsException.class));
    } catch (PantsException ignored) {
    }
  }
}
