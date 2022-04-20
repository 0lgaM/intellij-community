// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import java.io.File

class JpsCompilationData(val dataStorageRoot: File, val buildLogFile: File, categoriesWithDebugLevelNullable: String?) {
  val compiledModules: Set<String> = mutableSetOf()
  val compiledModuleTests: Set<String> = mutableSetOf()
  val builtArtifacts: Set<String> = mutableSetOf()
  var compiledClassesAreLoaded: Boolean = false
  var statisticsReported: Boolean = false
  var projectDependenciesResolved: Boolean = false

  val categoriesWithDebugLevel: String = categoriesWithDebugLevelNullable ?: ""
}
