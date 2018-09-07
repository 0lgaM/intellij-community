// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.testDiscovery;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Couple;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApiStatus.Experimental
public interface TestDiscoveryProducer {
  ExtensionPointName<TestDiscoveryProducer> EP = ExtensionPointName.create("com.intellij.testDiscoveryProducer");

  Logger LOG = Logger.getInstance(LocalTestDiscoveryProducer.class);

  @NotNull
  MultiMap<String, String> getDiscoveredTests(@NotNull Project project,
                                              @NotNull String classFQName,
                                              @Nullable String methodName,
                                              byte frameworkId);

  @NotNull
  default MultiMap<String, String> getDiscoveredTests(@NotNull Project project,
                                                      @NotNull List<Couple<String>> classesAndMethods,
                                                      byte frameworkId) {
    MultiMap<String, String> result = new MultiMap<>();
    classesAndMethods.forEach(couple -> result.putAllValues(getDiscoveredTests(project, couple.first, couple.second, frameworkId)));
    return result;
  }

  boolean isRemote();

  static void consumeDiscoveredTests(@NotNull Project project,
                                     @NotNull List<Couple<String>> classesAndMethods,
                                     byte frameworkId,
                                     @NotNull TestProcessor processor) {
    MultiMap<String, String> visitedTests = new MultiMap<String, String>() {
      @NotNull
      @Override
      protected Collection<String> createCollection() {
        return new THashSet<>();
      }
    };
    for (TestDiscoveryProducer producer : EP.getExtensions()) {
      for (Map.Entry<String, Collection<String>> entry : producer.getDiscoveredTests(project, classesAndMethods, frameworkId).entrySet()) {
        String className = entry.getKey();
        for (String methodRawName : entry.getValue()) {
          if (!visitedTests.get(className).contains(methodRawName)) {
            visitedTests.putValue(className, methodRawName);
            Couple<String> couple = extractParameter(methodRawName);
            if (!processor.process(className, couple.first, couple.second)) return;
          }
        }
      }
    }
  }

  @NotNull
  static Couple<String> extractParameter(@NotNull String rawName) {
    int idx = rawName.indexOf('[');
    return idx == -1 ?
           Couple.of(rawName, null) :
           Couple.of(rawName.substring(0, idx), rawName.substring(idx));
  }

  @FunctionalInterface
  interface TestProcessor {
    boolean process(@NotNull String className, @NotNull String methodName, @Nullable String parameter);
  }

  @FunctionalInterface
  interface PsiTestProcessor {
    boolean process(@NotNull PsiClass clazz, @NotNull PsiMethod method, @Nullable String parameter);
  }
}
