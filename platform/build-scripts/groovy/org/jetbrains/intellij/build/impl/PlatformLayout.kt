// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.library.JpsLibrary
import org.jetbrains.jps.model.module.JpsModule
import org.jetbrains.jps.model.module.JpsModuleReference
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Describes layout of the platform (*.jar files in IDE_HOME/lib directory).
 * <p>
 * By default it includes all modules specified in {@link org.jetbrains.intellij.build.ProductModulesLayout},
 * all libraries these modules depend on with scope 'Compile' or 'Runtime', and all project libraries from dependencies (with scope 'Compile'
 * or 'Runtime') of plugin modules for plugins which are {@link org.jetbrains.intellij.build.ProductModulesLayout#bundledPluginModules bundled}
 * (or prepared to be {@link org.jetbrains.intellij.build.ProductModulesLayout#setPluginModulesToPublish published}) with the product (except
 * project libraries which are explicitly included into layouts of all plugins depending on them by {@link BaseLayoutSpec#withProjectLibrary}).
 */
class PlatformLayout: BaseLayout() {
  val excludedProjectLibraries: MutableSet<String> = mutableSetOf()

  fun customize(body: Consumer<PlatformLayoutSpec>) {
    val spec = PlatformLayoutSpec(this)
    body.accept(spec)
  }

  fun withProjectLibrary(libraryName: String) {
    includedProjectLibraries.add(ProjectLibraryData(libraryName, "", ProjectLibraryData.PackMode.MERGED))
  }

  fun withProjectLibrary(libraryName: String, packMode: ProjectLibraryData.PackMode) {
    includedProjectLibraries.add(ProjectLibraryData(libraryName, "", packMode))
  }

  fun withProjectLibrary(data: ProjectLibraryData) {
    includedProjectLibraries.add(data)
  }

  /**
   * Exclude project library {@code libraryName} even if it's added to dependencies of some module or plugin included into the product
   */
  fun withoutProjectLibrary(libraryName: String) {
    excludedProjectLibraries.add(libraryName)
  }

  class PlatformLayoutSpec(private val layout: PlatformLayout): BaseLayoutSpec(layout) {
    /**
     * Exclude project library {@code libraryName} even if it's added to dependencies of some module or plugin included into the product
     */
    fun withoutProjectLibrary(libraryName: String) {
      layout.withoutProjectLibrary(libraryName)
    }
  }

  fun collectProjectLibrariesFromIncludedModules(context: BuildContext, consumer: BiConsumer<JpsLibrary, JpsModule>) {
    val libsToUnpack = projectLibrariesToUnpack.values()
    for (moduleName in getIncludedModuleNames()) {
      val module = context.findRequiredModule(moduleName)
      for (
        library in JpsJavaExtensionService.dependencies(module).includedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME).libraries) {
        if (library.createReference().parentReference is JpsModuleReference ||
            libsToUnpack.contains(library.name) ||
            excludedProjectLibraries.contains(library.name)) {
          continue
        }

        consumer.accept(library, module)
      }
    }
  }
}