// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.performance;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.PerformanceTrace;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.importing.GradleImportingTestCase;
import org.jetbrains.plugins.gradle.settings.GradleSystemSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.junit.Test;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.intellij.testFramework.PlatformTestUtil.assertTiming;

public class GradleImportPerformanceTest extends GradleImportingTestCase {

  public static final String TEST_DATA_PATH = System.getenv("gradle.performance.test.data.path");

  @Override
  protected void collectAllowedRoots(List<String> roots) {
    super.collectAllowedRoots(roots);
    roots.add(TEST_DATA_PATH);
  }

  @Override
  protected void setUpInWriteAction() throws Exception {
    File projectDir = new File(TEST_DATA_PATH);
    FileUtil.ensureExists(projectDir);
    myProjectRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir);
  }

  @Test
  public void testImportTiming() throws Exception {
    GradleSystemSettings.getInstance().setGradleVmOptions("-Dorg.gradle.jvmargs=-Xmx2g");
    importProjectUsingSingeModulePerGradleProject();
    long startTime = System.currentTimeMillis();
    importProjectUsingSingeModulePerGradleProject();
    long consumedTime = System.currentTimeMillis() - startTime;

    ProjectDataManager manager = ProjectDataManager.getInstance();
    final Collection<ExternalProjectInfo> data = manager.getExternalProjectsData(myProject, GradleConstants.SYSTEM_ID);
    final DataNode<ProjectData> rootNode = data.iterator().next().getExternalProjectStructure();
    final DataNode<PerformanceTrace> traceDataNode = ExternalSystemApiUtil.find(rootNode, PerformanceTrace.TRACE_NODE_KEY);
    final Map<String, Long> trace = traceDataNode.getData().getPerformanceTrace();


    assertTracedTimePercentAtLeast(trace, consumedTime, 90);

    final long gradleModelsTrace = sumByPrefix(trace, "Get model ");
    final long resolverChainTrace = sumByPrefix(trace, "Resolver chain ");
    final long dataServiceTrace  = sumByPrefix(trace, "Data import ");

    assertTiming("gradleModelsTrace = " + gradleModelsTrace, 30000, gradleModelsTrace);
    assertTiming("resolverChainTrace = " + resolverChainTrace, 2000, resolverChainTrace);
    assertTiming("dataServiceTrace = " + dataServiceTrace, 9000, dataServiceTrace);
  }

  @Test
  public void testImportPerSourceSetTiming() throws Exception {
    GradleSystemSettings.getInstance().setGradleVmOptions("-Dorg.gradle.jvmargs=-Xmx2g");
    importProject();
    long startTime = System.currentTimeMillis();
    importProject();
    long consumedTime = System.currentTimeMillis() - startTime;

    ProjectDataManager manager = ProjectDataManager.getInstance();
    final Collection<ExternalProjectInfo> data = manager.getExternalProjectsData(myProject, GradleConstants.SYSTEM_ID);
    final DataNode<ProjectData> rootNode = data.iterator().next().getExternalProjectStructure();
    final DataNode<PerformanceTrace> traceDataNode = ExternalSystemApiUtil.find(rootNode, PerformanceTrace.TRACE_NODE_KEY);
    final Map<String, Long> trace = traceDataNode.getData().getPerformanceTrace();

    assertTracedTimePercentAtLeast(trace, consumedTime, 95);

    final long gradleModelsTrace = sumByPrefix(trace, "Get model ");
    final long resolverChainTrace = sumByPrefix(trace, "Resolver chain ");
    final long dataServiceTrace  = sumByPrefix(trace, "Data import ");

    assertTiming("gradleModelsTrace = " + gradleModelsTrace, 70000, gradleModelsTrace);
    assertTiming("resolverChainTrace = " + resolverChainTrace, 1200, resolverChainTrace);
    assertTiming("dataServiceTrace = " + dataServiceTrace, 9000, dataServiceTrace);
  }

  protected long sumByPrefix(Map<String, Long> trace, String prefix) {
    return trace.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .mapToLong(Map.Entry::getValue)
                .sum();
  }


  private static void assertTracedTimePercentAtLeast(@NotNull final Map<String, Long> trace, long time, int threshold) {
    final long tracedTime = trace.get("Gradle data obtained")
                            + trace.get("Gradle project data processed")
                            + trace.get("Data import total");

    double percent = (double)tracedTime / time * 100;
    assertTrue( String.format("Test time [%d] traced time [%d], percentage [%.2f]", time, tracedTime, percent), percent > threshold && percent < 100);
  }
}
