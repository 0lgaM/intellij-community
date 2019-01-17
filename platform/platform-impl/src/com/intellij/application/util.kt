// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.application

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

@JvmOverloads
inline fun runInAllowSaveMode(isSaveAllowed: Boolean = true, task: () -> Unit) {
  val app = ApplicationManagerEx.getApplicationEx()
  if (isSaveAllowed == app.isSaveAllowed) {
    task()
    return
  }

  app.isSaveAllowed = isSaveAllowed
  try {
    task()
  }
  finally {
    app.isSaveAllowed = !isSaveAllowed
  }
}

/**
 * Execute coroutine on pooled thread. Uncaught error will be logged.
 *
 * @see com.intellij.openapi.application.Application.executeOnPooledThread
 */
val pooledThreadContext: CoroutineContext = ApplicationThreadPoolDispatcher().plus(CoroutineExceptionHandler { _, throwable ->
  // otherwise not possible to test - will be "Start Failed: Internal error" because in tests app not yet started / not created
  val app = ApplicationManager.getApplication()
  if (app == null || app.isUnitTestMode) {
    Logger.getInstance("#com.intellij.application.impl.ApplicationImpl").error(throwable)
  }
  else {
    PluginManager.processException(throwable)
  }
})

object PooledScope : CoroutineScope {
  override val coroutineContext = pooledThreadContext
}

// no need to implement isDispatchNeeded - Kotlin correctly uses the same thread if coroutines executes sequentially,
// and if launch/async is used, it is correct and expected that coroutine will be dispatched to another pooled thread.
private class ApplicationThreadPoolDispatcher : CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    AppExecutorUtil.getAppExecutorService().execute(block)
  }

  override fun toString() = AppExecutorUtil.getAppExecutorService().toString()
}