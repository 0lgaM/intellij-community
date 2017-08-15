/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.xdebugger.attach;

import com.intellij.execution.process.ProcessInfo;
import com.intellij.remote.RemoteSdkCredentials;
import org.jetbrains.annotations.NotNull;

public class RemoteAttachSettings implements AttachToProcessSettings {
  @NotNull private ProcessInfo myInfo;
  @NotNull private RemoteSdkCredentials myCredentials;

  public RemoteAttachSettings(@NotNull ProcessInfo info, @NotNull RemoteSdkCredentials credentials) {
    myInfo = info;
    myCredentials = credentials;
  }

  @NotNull
  @Override
  public ProcessInfo getInfo() {
    return myInfo;
  }

  @NotNull
  public RemoteSdkCredentials getCredentials() {
    return myCredentials;
  }

  @NotNull
  @Override
  public String getText() {
    return myInfo.getExecutableName();
  }
}