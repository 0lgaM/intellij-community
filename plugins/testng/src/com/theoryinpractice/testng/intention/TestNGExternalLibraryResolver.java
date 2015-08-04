/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.theoryinpractice.testng.intention;

import com.intellij.codeInsight.daemon.quickFix.ExternalLibraryDescriptor;
import com.intellij.codeInsight.daemon.quickFix.ExternalLibraryResolver;
import com.intellij.openapi.module.Module;
import com.intellij.util.PathUtil;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author nik
 */
public class TestNGExternalLibraryResolver extends ExternalLibraryResolver {
  private static final Set<String> TEST_NG_ANNOTATIONS = ContainerUtil.set(
    "Test", "BeforeClass", "BeforeGroups", "BeforeMethod", "BeforeSuite", "BeforeTest", "AfterClass", "AfterGroups", "AfterMethod",
    "AfterSuite", "AfterTest", "Configuration"
  );
  private static final ExternalLibraryDescriptor TESTNG_DESCRIPTOR = new ExternalLibraryDescriptor("org.testng", "testng", null) {
    @NotNull
    @Override
    public List<String> locateLibraryClassesRoots(@NotNull Module contextModule) {
      return Collections.singletonList(PathUtil.getJarPathForClass(Test.class));
    }
  };

  @Nullable
  @Override
  public ExternalClassResolveResult resolveClass(@NotNull String shortClassName, @NotNull ThreeState isAnnotation) {
    if (TEST_NG_ANNOTATIONS.contains(shortClassName)) {
      return new ExternalClassResolveResult("org.testng.annotations." + shortClassName, TESTNG_DESCRIPTOR);
    }
    return null;
  }
}
