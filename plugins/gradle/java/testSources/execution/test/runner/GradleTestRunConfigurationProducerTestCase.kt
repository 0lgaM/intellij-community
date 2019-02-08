// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.execution.test.runner

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContextImpl
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.MapDataContext
import org.jetbrains.plugins.gradle.importing.GradleBuildScriptBuilderEx
import org.jetbrains.plugins.gradle.importing.GradleImportingTestCase
import org.jetbrains.plugins.gradle.util.findChildByElementType
import org.jetbrains.plugins.gradle.util.findChildByType
import org.jetbrains.plugins.gradle.util.findChildrenByType
import org.jetbrains.plugins.gradle.util.runReadActionAndWait
import org.junit.runners.Parameterized

abstract class GradleTestRunConfigurationProducerTestCase : GradleImportingTestCase() {

  protected fun getContextByLocation(vararg elements: PsiElement): ConfigurationContext {
    assertTrue(elements.isNotEmpty())
    val dataContext = MapDataContext().apply {
      put(LangDataKeys.PROJECT, myProject)
      put(LangDataKeys.MODULE, ModuleUtilCore.findModuleForPsiElement(elements[0]))
      put(Location.DATA_KEY, PsiLocation.fromPsiElement(elements[0]))
      put(Location.DATA_KEYS, elements.map { PsiLocation.fromPsiElement(it) }.toTypedArray())
    }
    return object : ConfigurationContext(elements[0]) {
      override fun getDataContext() = dataContext
      override fun containsMultipleSelection() = elements.size > 1
    }
  }

  protected fun getConfigurationFromContext(context: ConfigurationContext): ConfigurationFromContextImpl {
    val fromContexts = context.configurationsFromContext
    val fromContext = fromContexts?.firstOrNull()
    assertNotNull("Gradle configuration from context not found", fromContext)
    return fromContext as ConfigurationFromContextImpl
  }

  protected inline fun <reified P : GradleTestRunConfigurationProducer> getConfigurationProducer(): P {
    return RunConfigurationProducer.getInstance(P::class.java)
  }

  protected inline fun <reified P : GradleTestRunConfigurationProducer> assertConfigurationFromContext(
    expectedSettings: String,
    vararg elements: PsiElement
  ) = runReadActionAndWait {
    val context = getContextByLocation(*elements)
    val configurationFromContext = getConfigurationFromContext(context)
    val producer = configurationFromContext.configurationProducer as P
    val configuration = configurationFromContext.configuration as ExternalSystemRunConfiguration
    assertTrue(producer.setupConfigurationFromContext(configuration, context, Ref(context.psiLocation)))
    if (producer !is PatternGradleConfigurationProducer) {
      assertTrue(producer.isConfigurationFromContext(configuration, context))
    }
    producer.onFirstRun(configurationFromContext, context, Runnable {})
    assertEquals(expectedSettings, configuration.settings.toString().trim())
  }

  protected fun generateAndImportTemplateProject(): ProjectData {
    val testCaseFile = createProjectSubFile("src/test/java/TestCase.java", """
      import org.junit.Test;
      public class TestCase extends AbstractTestCase {
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val packageTestCaseFile = createProjectSubFile("src/test/java/pkg/TestCase.java", """
      package pkg;
      import org.junit.Test;
      public class TestCase extends AbstractTestCase {
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val automationTestCaseFile = createProjectSubFile("automation/AutomationTestCase.java", """
      import org.junit.Test;
      public class AutomationTestCase extends AbstractTestCase {
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val abstractTestCaseFile = createProjectSubFile("src/test/java/AbstractTestCase.java", """
      import org.junit.Test;
      public class AbstractTestCase {
        @Test public void test() {}
      }
    """.trimIndent())
    val moduleTestCaseFile = createProjectSubFile("module/src/test/java/ModuleTestCase.java", """
      import org.junit.Test;
      public class ModuleTestCase extends AbstractTestCase {
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val groovyTestCaseFile = createProjectSubFile("src/test/groovy/GroovyTestCase.groovy", """
      import org.junit.Test;
      public class GroovyTestCase extends AbstractTestCase {
        @Test public void 'Don\\\'t use single . quo\\"tes'() {}
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val myModuleTestCaseFile = createProjectSubFile("my module/src/test/groovy/MyModuleTestCase.groovy", """
      import org.junit.Test;
      public class MyModuleTestCase extends AbstractTestCase {
        @Test public void test1() {}
        @Test public void test2() {}
        @Test public void test3() {}
      }
    """.trimIndent())
    val buildScript = GradleBuildScriptBuilderEx()
      .withJavaPlugin()
      .withJUnit("4.12")
      .withGroovyPlugin("2.4.14")
      .addPrefix("""
        sourceSets {
          automation.java.srcDirs = ['automation']
          automation.compileClasspath += sourceSets.test.runtimeClasspath
        }
        task autoTest(type: Test) {
          testClassesDirs = sourceSets.automation.output.classesDirs
        }
        task automationTest(type: Test) {
          testClassesDirs = sourceSets.automation.output.classesDirs
        }
      """.trimIndent())
      .addPrefix("""
        task myTestsJar(type: Jar, dependsOn: testClasses) {
          baseName = "test-${'$'}{project.archivesBaseName}"
          from sourceSets.automation.output
        }
        configurations {
          testArtifacts
        }
        artifacts {
          testArtifacts  myTestsJar
        }
      """.trimIndent())
    val moduleBuildScript = GradleBuildScriptBuilderEx()
      .withJavaPlugin()
      .withJUnit("4.12")
      .withGroovyPlugin("2.4.14")
      .addDependency("testCompile project(path: ':', configuration: 'testArtifacts')")
    createSettingsFile("""
      rootProject.name = 'project'
      include 'module', 'my module'
    """.trimIndent())
    createProjectSubFile("module/build.gradle", moduleBuildScript.generate())
    createProjectSubFile("my module/build.gradle", moduleBuildScript.generate())
    importProject(buildScript.generate())
    assertModules("project", "project.main", "project.test", "project.automation",
                  "project.module", "project.module.main", "project.module.test",
                  "project.my_module", "project.my_module.main", "project.my_module.test")
    val automationTestCase = extractJavaClassData(automationTestCaseFile)
    val testCase = extractJavaClassData(testCaseFile)
    val abstractTestCase = extractJavaClassData(abstractTestCaseFile)
    val moduleTestCase = extractJavaClassData(moduleTestCaseFile)
    val packageTestCase = extractJavaClassData(packageTestCaseFile)
    val groovyTestCase = extractGroovyClassData(groovyTestCaseFile)
    val myModuleTestCase = extractGroovyClassData(myModuleTestCaseFile)
    return ProjectData(
      ModuleData("project", testCase, packageTestCase, automationTestCase, abstractTestCase, groovyTestCase),
      ModuleData("module", moduleTestCase),
      ModuleData("my module", myModuleTestCase)
    )
  }

  private fun extractJavaClassData(file: VirtualFile) = runReadActionAndWait {
    val psiManager = PsiManager.getInstance(myProject)
    val psiFile = psiManager.findFile(file)!!
    val psiClass = psiFile.findChildByType<PsiClass>()
    val psiMethods = psiClass.findChildrenByType<PsiMethod>()
    val methods = psiMethods.map { MethodData(it.name, it) }
    ClassData(psiClass.qualifiedName!!, psiClass, methods)
  }

  private fun extractGroovyClassData(file: VirtualFile) = runReadActionAndWait {
    val psiManager = PsiManager.getInstance(myProject)
    val psiFile = psiManager.findFile(file)!!
    val psiClass = psiFile.findChildByType<PsiClass>()
    val classBody = psiClass.findChildByElementType("CLASS_BODY")
    val psiMethods = classBody.findChildrenByType<PsiMethod>()
    val methods = psiMethods.map { MethodData(it.name, it) }
    ClassData(psiClass.qualifiedName!!, psiClass, methods)
  }


  protected open class Mapping<D>(val data: Map<String, D>) {
    operator fun get(key: String): D = data.getValue(key)
  }

  protected class ProjectData(
    vararg modules: ModuleData
  ) : Mapping<ModuleData>(modules.map { it.name to it }.toMap())

  protected class ModuleData(
    val name: String,
    vararg classes: ClassData
  ) : Mapping<ClassData>(classes.map { it.name to it }.toMap())

  protected class ClassData(
    val name: String,
    val element: PsiClass,
    methods: List<MethodData>
  ) : Mapping<MethodData>(methods.map { it.name to it }.toMap())

  protected class MethodData(
    val name: String,
    val element: PsiMethod
  )

  companion object {
    /**
     * It's sufficient to run the test against one gradle version
     */
    @Parameterized.Parameters(name = "with Gradle-{0}")
    @JvmStatic
    fun tests(): Collection<Array<out String>> = arrayListOf(arrayOf(GradleImportingTestCase.BASE_GRADLE_VERSION))
  }
}