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
package com.intellij.openapi.vcs.changes.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.util.containers.ContainerUtil
import kotlin.platform.platformStatic

abstract class ChangeListRemoveConfirmation() {
  
  abstract fun askIfShouldRemoveChangeLists(ask: List<LocalChangeList>): Boolean
  
  companion object {
    platformStatic
    fun processLists(project: Project, allLists: Collection<LocalChangeList>, ask: ChangeListRemoveConfirmation) {
      val confirmationAsked = ContainerUtil.newIdentityTroveSet<LocalChangeList>()
      val doNotRemove = ContainerUtil.newIdentityTroveSet<LocalChangeList>()

      for (vcs in ProjectLevelVcsManager.getInstance(project).getAllActiveVcss()) {
        for (list in allLists) {
          val permission = vcs.mayRemoveChangeList(list)
          if (permission != null) {
            confirmationAsked.add(list)
          }
          if (java.lang.Boolean.FALSE == permission) {
            doNotRemove.add(list)
            break
          }
        }
      }

      val toAsk = allLists.filter { !(it in confirmationAsked || it in doNotRemove) }
      if (toAsk.isNotEmpty() && !ask.askIfShouldRemoveChangeLists(toAsk)) {
        doNotRemove.addAll(toAsk)
      }
      allLists.filter { !(it in doNotRemove) }.forEach { ChangeListManager.getInstance(project).removeChangeList(it.getName()) }
    }
  }
}