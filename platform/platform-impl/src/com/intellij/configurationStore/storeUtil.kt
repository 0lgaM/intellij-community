// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:JvmName("StoreUtil")

package com.intellij.configurationStore

import com.intellij.diagnostic.IdeErrorsDialog
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.stateStore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.runBlocking

private val LOG = Logger.getInstance("#com.intellij.openapi.components.impl.stores.StoreUtil")

/**
 * Do not use this method in tests, instead directly save using state store.
 */
@JvmOverloads
fun saveSettings(componentManager: ComponentManager, isForceSavingAllSettings: Boolean = false) {
  val currentThread = Thread.currentThread()
  ShutDownTracker.getInstance().registerStopperThread(currentThread)
  try {
    runBlocking {
      componentManager.stateStore.save(isForceSavingAllSettings = isForceSavingAllSettings)
    }
  }
  catch (e: UnresolvedReadOnlyFilesException) {
    LOG.info(e)
  }
  catch (e: Throwable) {
    if (ApplicationManager.getApplication().isUnitTestMode) {
      LOG.error("Save settings failed", e)
    }
    else {
      LOG.warn("Save settings failed", e)
    }

    val messagePostfix = "Please restart ${ApplicationNamesInfo.getInstance().fullProductName}</p>" +
                         (if (ApplicationManagerEx.getApplicationEx().isInternal) "<p>" + StringUtil.getThrowableText(e) + "</p>" else "")

    val pluginId = IdeErrorsDialog.findPluginId(e)
    val notification = if (pluginId == null) {
      Notification("Settings Error", "Unable to save settings", "<p>Failed to save settings. $messagePostfix",
                   NotificationType.ERROR)
    }
    else {
      PluginManagerCore.disablePlugin(pluginId.idString)
      Notification("Settings Error", "Unable to save plugin settings",
                   "<p>The plugin <i>$pluginId</i> failed to save settings and has been disabled. $messagePostfix",
                   NotificationType.ERROR)
    }
    notification.notify(componentManager as? Project)
  }
  finally {
    ShutDownTracker.getInstance().unregisterStopperThread(currentThread)
  }
}

fun <T> getStateSpec(persistentStateComponent: PersistentStateComponent<T>): State {
  return getStateSpecOrError(persistentStateComponent.javaClass)
}

fun getStateSpecOrError(componentClass: Class<out PersistentStateComponent<*>>): State {
  return getStateSpec(componentClass)
         ?: throw PluginManagerCore.createPluginException("No @State annotation found in $componentClass", null, componentClass)
}

fun getStateSpec(originalClass: Class<*>): State? {
  var aClass = originalClass
  while (true) {
    val stateSpec = aClass.getAnnotation(State::class.java)
    if (stateSpec != null) {
      return stateSpec
    }

    aClass = aClass.superclass ?: break
  }
  return null
}

/**
 * @param isForceSavingAllSettings Whether to force save non-roamable component configuration.
 */
fun saveDocumentsAndProjectsAndApp(isForceSavingAllSettings: Boolean) {
  FileDocumentManager.getInstance().saveAllDocuments()
  saveProjectsAndApp(isForceSavingAllSettings)
}

/**
 * @param isForceSavingAllSettings Whether to force save non-roamable component configuration.
 */
internal fun saveProjectsAndApp(isForceSavingAllSettings: Boolean) {
  val start = System.currentTimeMillis()
  ApplicationManager.getApplication().saveSettings(isForceSavingAllSettings)

  val projectManager = ProjectManager.getInstance()
  if (projectManager is ProjectManagerEx) {
    projectManager.flushChangedProjectFileAlarm()
  }

  for (project in projectManager.openProjects) {
    saveProject(project, isForceSavingAllSettings)
  }

  val duration = System.currentTimeMillis() - start
  LOG.info("saveProjectsAndApp took $duration ms")
}

fun saveProject(project: Project, isForceSavingAllSettings: Boolean) {
  if (isForceSavingAllSettings && project is ProjectEx) {
    project.save(true)
  }
  else {
    project.save()
  }
}