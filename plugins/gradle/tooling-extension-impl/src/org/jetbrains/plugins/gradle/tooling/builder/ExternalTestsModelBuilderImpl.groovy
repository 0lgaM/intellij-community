// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.tooling.builder

import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.jetbrains.annotations.NotNull
import org.jetbrains.plugins.gradle.model.tests.DefaultExternalTestSourceMapping
import org.jetbrains.plugins.gradle.model.tests.DefaultExternalTestsModel
import org.jetbrains.plugins.gradle.model.tests.ExternalTestSourceMapping
import org.jetbrains.plugins.gradle.model.tests.ExternalTestsModel
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService

@CompileStatic
class ExternalTestsModelBuilderImpl implements ModelBuilderService {
  @Override
  boolean canBuild(String modelName) {
    return ExternalTestsModel.name == modelName
  }

  @Override
  Object buildAll(String modelName, Project project) {
    def defaultTestsModel = new DefaultExternalTestsModel()
    defaultTestsModel.sourceTestMappings = getMapping(project)
    return defaultTestsModel
  }

  private static List<ExternalTestSourceMapping> getMapping(Project project) {
    def taskToClassesDirs = new LinkedHashMap<Test, Set<String>>()
    for (def task : project.tasks) {
      if (task instanceof Test) {
        def test = (Test) task
        def classFiles = getPaths(test.testClassesDirs)
        taskToClassesDirs[test] = classFiles
      }
    }
    def sourceSetContainer = project.findProperty("sourceSets") as SourceSetContainer
    if (sourceSetContainer == null) return Collections.emptyList()
    def classesDirToSourceDirs = new LinkedHashMap<String, Set<String>>()
    for (def sourceSet : sourceSetContainer) {
      def sourceFolders = getPaths(sourceSet.allSource.sourceDirectories)
      for (def classDirectory : getPaths(sourceSet.output)) {
        def storedSourceFolders = classesDirToSourceDirs.getOrDefault(classDirectory, new LinkedHashSet<>())
        storedSourceFolders.addAll(sourceFolders)
        classesDirToSourceDirs.put(classDirectory, storedSourceFolders)
      }
    }
    def testSourceMappings = new ArrayList<ExternalTestSourceMapping>()
    for (def entry : taskToClassesDirs.entrySet()) {
      def sourceFolders = new LinkedHashSet<String>()
      for (def classDirectory : entry.value) {
        def storedSourceFolders = classesDirToSourceDirs[classDirectory]
        if (storedSourceFolders == null) continue
        sourceFolders.addAll(storedSourceFolders)
      }
      def task = entry.key
      def taskProjectPath = task.project.path == ":" ? "" : task.project.path
      def cleanTestTaskName = "clean" + task.name.capitalize()
      def defaultExternalTestSourceMapping = new DefaultExternalTestSourceMapping()
      defaultExternalTestSourceMapping.testName = task.name
      defaultExternalTestSourceMapping.testTaskPath = task.path
      defaultExternalTestSourceMapping.cleanTestTaskPath = taskProjectPath + ":" + cleanTestTaskName
      defaultExternalTestSourceMapping.sourceFolders = sourceFolders
      testSourceMappings.add(defaultExternalTestSourceMapping)
    }
    return testSourceMappings
  }

  private static Set<String> getPaths(FileCollection files) {
    def paths = new LinkedHashSet<String>()
    for (def file : files.files) {
      paths.add(file.absolutePath)
    }
    return paths
  }

  @NotNull
  @Override
  ErrorMessageBuilder getErrorMessageBuilder(@NotNull Project project, @NotNull Exception e) {
    return ErrorMessageBuilder.create(project, e, "Tests model errors")
  }
}
