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
package com.intellij.usages.impl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Factory
import com.intellij.usages.*

public class ServerUsageViewManager(val project: Project) : UsageViewManagerImpl(project) {
  override fun showUsages(searchedFor: Array<UsageTarget>,
                          foundUsages: Array<Usage>,
                          presentation: UsageViewPresentation,
                          factory: Factory<UsageSearcher>): UsageView {
    val usageView = createUsageView(searchedFor, foundUsages, presentation, factory)

    return usageView
  }

  override fun createUsageView(targets: Array<UsageTarget>,
                               usages: Array<Usage>,
                               presentation: UsageViewPresentation,
                               usageSearcherFactory: Factory<UsageSearcher>): UsageView {
    val usageView = ServerUsageView(project, presentation, targets, usageSearcherFactory)
    ApplicationManager.getApplication().runReadAction {
      usages.forEach { usageView.appendUsage(it) }
    }
    return usageView
  }
}
