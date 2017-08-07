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
package org.jetbrains.plugins.gradle.tooling.internal.backRefCollector;

import com.intellij.util.Consumer;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.backwardRefs.BackwardReferenceIndexUtil;
import org.jetbrains.backwardRefs.JavacReferenceIndexWriter;
import org.jetbrains.backwardRefs.javac.ast.JavacReferenceIndexListener;
import org.jetbrains.backwardRefs.javac.ast.api.JavacFileData;

import java.io.File;

public class ReferenceIndexJavacPlugin implements Plugin {
  public static final String PLUGIN_NAME = "ReferenceIndexJavacPlugin";
  public static final String FORK_ARG = "fork";
  public static final String INDEX_PATH_ARG = "index.path=";

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public void init(JavacTask task, String... args) {
    File dir = getIndexDir(args);
    if (dir == null) return;

    boolean inProcess = false;
    try {
      inProcess = ReferenceIndexHolder.ourInProcess;
    }
    catch (NoClassDefFoundError ignored) { }

    if (inProcess) {
      setupForInProcessMode(task);
    } else {
      //don't setup
    }
  }

  private static void setupForInProcessMode(@NotNull JavacTask task) {
    task.addTaskListener(new JavacReferenceIndexListener(false, new Consumer<JavacFileData>() {
      @Override
      public void consume(JavacFileData data) {
        BackwardReferenceIndexUtil.registerFile(data.getFilePath(), data.getRefs(), data.getDefs(),
                                                (JavacReferenceIndexWriter)ReferenceIndexHolder.ourIndexWriter);
      }
    }, task, true));
  }

  private static void setupForForkMode(@NotNull JavacTask task, @NotNull String indexPath) {
    //TODO
  }

  @Nullable
  private static File getIndexDir(String[] args) {
    for (String arg : args) {
      if (arg.startsWith(INDEX_PATH_ARG)) {
        return new File(arg.substring(INDEX_PATH_ARG.length()));
      }
    }
    return null;
  }
}
