/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.run.runner;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.tasks.LaunchResult;
import com.android.tools.idea.run.util.LaunchStatus;
import com.google.idea.blaze.android.run.deployinfo.BlazeAndroidDeployInfo;
import com.intellij.execution.Executor;

/** Checks APKs to see if they are debuggable and warn the user if they aren't. */
public class CheckApkDebuggableTask extends CheckApkDebuggableTaskBase {
  CheckApkDebuggableTask(BlazeAndroidDeployInfo deployInfo) {
    super(deployInfo);
  }

  @Override
  public LaunchResult run(
      Executor executor, IDevice device, LaunchStatus launchStatus, ConsolePrinter consolePrinter) {
    checkApkDebuggableTaskDelegate(deployInfo, device, consolePrinter);
    return LaunchResult.success(); // Don't block deployment.
  }
}
