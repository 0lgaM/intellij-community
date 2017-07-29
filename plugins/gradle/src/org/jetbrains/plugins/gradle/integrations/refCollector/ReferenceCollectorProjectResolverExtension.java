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
package org.jetbrains.plugins.gradle.integrations.refCollector;

import com.google.common.collect.ImmutableSet;
import com.intellij.compiler.CompilerReferenceService;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Consumer;
import com.intellij.util.PathUtil;
import com.intellij.util.io.PersistentHashMap;
import gnu.trove.TObjectIntHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.backwardRefs.LightRef;
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension;
import org.jetbrains.plugins.gradle.tooling.internal.backRefCollector.GradleJavacReferenceIndexWriter;
import org.jetbrains.plugins.gradle.tooling.internal.backRefCollector.ReferenceIndexJavacPlugin;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Order(ExternalSystemConstants.UNORDERED)
public class ReferenceCollectorProjectResolverExtension extends AbstractProjectResolverExtension {
  private static final Logger LOG = Logger.getInstance(ReferenceCollectorProjectResolverExtension.class);

  @Override
  public void enhanceTaskProcessing(@NotNull List<String> taskNames,
                                    @Nullable String jvmAgentSetup,
                                    @NotNull Consumer<String> initScriptConsumer) {
    if (false && CompilerReferenceService.isEnabled() && (taskNames.contains(":classes") || taskNames.contains(":testClasses"))) {
      InputStream stream =
        ReferenceIndexJavacPlugin.class.getResourceAsStream("/org/jetbrains/plugins/gradle/tooling/internal/backRefCollector/init.gradle");
      try {
        if (stream == null) {
          throw new IllegalStateException("can't find reference collector gradle script");
        }
        ImmutableSet<Class> requiredClasses = ImmutableSet.of(ReferenceIndexJavacPlugin.class,
                                                 LightRef.class,
                                                 TObjectIntHashMap.class,
                                                 PersistentHashMap.class,
                                                 Consumer.class);
        String initScript = FileUtil.loadTextAndClose(stream)
          .replaceAll(Pattern.quote("${EXTENSIONS_JARS_PATH}"), getToolingExtensionsJarPaths(requiredClasses, true))
          .replaceAll(Pattern.quote("${JAVAC_PLUGIN_PATH}"), getToolingExtensionsJarPaths(requiredClasses, false));
        initScriptConsumer.consume(initScript);
      }
      catch (Exception e) {
        LOG.warn("can't generate reference collector gradle script", e);
      }
      finally {
        StreamUtil.closeStream(stream);
      }
    }
  }

  @NotNull
  private static String getToolingExtensionsJarPaths(@NotNull Set<Class> toolingExtensionClasses, boolean prepareForGradle) {
    Stream<String> paths = toolingExtensionClasses.stream().map(c -> {
      String path = PathManager.getJarPathForClass(c);
      return path == null ? null : PathUtil.getCanonicalPath(path);
    });
    if (prepareForGradle) {
      paths = paths.map(p -> StringUtil.wrapWithDoubleQuote(p));
    }
    return paths.collect(Collectors.joining(prepareForGradle ? "," : ":"));
  }

  @NotNull
  @Override
  public List<Pair<String, String>> getExtraJvmArgs() {
    return Collections.singletonList(Pair.create("ref_index_path", "/home/user/index"));
  }

  @NotNull
  @Override
  public Set<Class> getToolingExtensionsClasses() {
    return ImmutableSet.of(ReferenceIndexJavacPlugin.class, GradleJavacReferenceIndexWriter.class, TObjectIntHashMap.class);
  }
}
