// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment")

package com.intellij.serviceContainer

import com.intellij.diagnostic.ActivityCategory
import com.intellij.diagnostic.LoadingState
import com.intellij.diagnostic.PluginException
import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressIndicatorProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.picocontainer.ComponentAdapter
import org.picocontainer.PicoContainer

@OptIn(ExperimentalCoroutinesApi::class)
internal sealed class BaseComponentAdapter(@JvmField internal val componentManager: ComponentManagerImpl,
                                           @JvmField val pluginDescriptor: PluginDescriptor,
                                           @field:Volatile private var deferred: CompletableDeferred<Any>,
                                           private var implementationClass: Class<*>?) : ComponentAdapter {
  @Volatile
  private var initializing = false

  val pluginId: PluginId
    get() = pluginDescriptor.pluginId

  protected abstract val implementationClassName: String

  protected abstract fun isImplementationEqualsToInterface(): Boolean

  final override fun getComponentImplementation() = getImplementationClass()

  @Synchronized
  fun getImplementationClass(): Class<*> {
    var result = implementationClass
    if (result == null) {
      try {
        result = componentManager.loadClass<Any>(implementationClassName, pluginDescriptor)
      }
      catch (e: ClassNotFoundException) {
        throw PluginException("Failed to load class: $implementationClassName", e, pluginDescriptor.pluginId)
      }
      implementationClass = result
    }
    return result
  }

  fun getInitializedInstance() = if (deferred.isCompleted) deferred.getCompleted() else null

  @Suppress("DeprecatedCallableAddReplaceWith")
  @Deprecated("Do not use")
  final override fun getComponentInstance(container: PicoContainer): Any? {
    //LOG.error("Use getInstance() instead")
    return getInstance(componentManager, null)
  }

  fun <T : Any> getInstance(componentManager: ComponentManagerImpl, keyClass: Class<T>?, createIfNeeded: Boolean = true): T? {
    if (deferred.isCompleted) {
      @Suppress("UNCHECKED_CAST")
      return deferred.getCompleted() as T
    }
    else if (!createIfNeeded) {
      return null
    }

    LoadingState.COMPONENTS_REGISTERED.checkOccurred()
    val indicator = ProgressIndicatorProvider.getGlobalProgressIndicator()
    checkContainerIsActive(componentManager, indicator)
    return getInstanceUncached(componentManager, keyClass, indicator)
  }

  fun <T : Any> getInstanceUncached(componentManager: ComponentManagerImpl, keyClass: Class<T>?, indicator: ProgressIndicator?): T {
    val activityCategory = if (StartUpMeasurer.isEnabled()) getActivityCategory(componentManager) else null
    val beforeLockTime = if (activityCategory == null) -1 else StartUpMeasurer.getCurrentTime()

    synchronized(this) {
      if (deferred.isCompleted) {
        if (activityCategory != null) {
          val end = StartUpMeasurer.getCurrentTime()
          if ((end - beforeLockTime) > 100) {
            // do not report plugin id - not clear who calls us and how we should interpret this delay - total duration vs own duration is enough for plugin cost measurement
            StartUpMeasurer.addCompletedActivity(beforeLockTime, end, implementationClassName, ActivityCategory.SERVICE_WAITING, /* pluginId = */ null)
          }
        }

        @Suppress("UNCHECKED_CAST")
        return deferred.getCompleted() as T
      }

      return getInstanceUncachedImpl(keyClass, componentManager, indicator, activityCategory)
    }
  }

  private fun <T : Any> getInstanceUncachedImpl(keyClass: Class<T>?,
                                                componentManager: ComponentManagerImpl,
                                                indicator: ProgressIndicator?,
                                                activityCategory: ActivityCategory?): T {
    if (initializing) {
      LOG.error(PluginException("Cyclic service initialization: ${toString()}", pluginId))
    }

    try {
      initializing = true

      val startTime = StartUpMeasurer.getCurrentTime()
      val implementationClass: Class<T>
      if (keyClass != null && isImplementationEqualsToInterface()) {
        implementationClass = keyClass
        this.implementationClass = keyClass
      }
      else {
        @Suppress("UNCHECKED_CAST")
        implementationClass = getImplementationClass() as Class<T>
        // check after loading class once again
        checkContainerIsActive(componentManager, indicator)
      }

      val instance = doCreateInstance(componentManager, implementationClass, indicator)
      activityCategory?.let { category ->
        val end = StartUpMeasurer.getCurrentTime()
        if (activityCategory != ActivityCategory.MODULE_SERVICE || (end - startTime) > StartUpMeasurer.MEASURE_THRESHOLD) {
          StartUpMeasurer.addCompletedActivity(startTime, end, implementationClassName, category, pluginId.idString)
        }
      }

      deferred.complete(instance)
      return instance
    }
    finally {
      initializing = false
    }
  }

  fun <T : Any> getInstanceAsync(componentManager: ComponentManagerImpl, keyClass: Class<T>?): Deferred<T> {
    if (deferred.isCompleted || initializing) {
      @Suppress("UNCHECKED_CAST")
      return deferred as Deferred<T>
    }

    val activityCategory = if (StartUpMeasurer.isEnabled()) getActivityCategory(componentManager) else null
    synchronized(this) {
      if (deferred.isCompleted) {
        @Suppress("UNCHECKED_CAST")
        return deferred as Deferred<T>
      }

      getInstanceUncachedImpl(keyClass = keyClass,
                              componentManager = componentManager,
                              indicator = null,
                              activityCategory = activityCategory)
      @Suppress("UNCHECKED_CAST")
      return deferred as Deferred<T>
    }
  }

  /**
   * Indicator must be always passed - if under progress, then ProcessCanceledException will be thrown instead of AlreadyDisposedException.
   */
  private fun checkContainerIsActive(componentManager: ComponentManagerImpl, indicator: ProgressIndicator?) {
    if (indicator != null) {
      checkCanceledIfNotInClassInit()
    }

    if (componentManager.isDisposed) {
      throwAlreadyDisposedError(toString(), componentManager, indicator)
    }
    if (!isGettingServiceAllowedDuringPluginUnloading(pluginDescriptor)) {
      componentManager.componentContainerIsReadonly?.let {
        val error = AlreadyDisposedException("Cannot create ${toString()} because container in read-only mode (reason=$it, container=${componentManager})")
        throw if (indicator == null) error else ProcessCanceledException(error)
      }
    }
  }

  internal fun throwAlreadyDisposedError(componentManager: ComponentManagerImpl, indicator: ProgressIndicator?) {
    throwAlreadyDisposedError(toString(), componentManager, indicator)
  }

  protected abstract fun getActivityCategory(componentManager: ComponentManagerImpl): ActivityCategory?

  protected abstract fun <T : Any> doCreateInstance(componentManager: ComponentManagerImpl, implementationClass: Class<T>, indicator: ProgressIndicator?): T
}