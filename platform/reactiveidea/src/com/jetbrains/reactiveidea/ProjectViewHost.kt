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
package com.jetbrains.reactiveidea

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPSIPane
import com.intellij.ide.projectView.impl.GroupByTypeComparator
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.models.MapModel
import com.jetbrains.reactivemodel.models.PrimitiveModel
import com.jetbrains.reactivemodel.util.*
import java.util.HashMap


public class ProjectViewHost(val project: Project,
                             val reactiveModel: ReactiveModel,
                             val path: Path,
                             val lifetime: Lifetime,
                             init: Initializer) : Host, DataProvider {
  override fun getData(dataId: String?): Any? {
    if (CommonDataKeys.PROJECT.`is`(dataId)) {
      return project
    }
    return null
  }

  val projectView = ProjectView.getInstance(project)
  val viewPane = projectView.getProjectViewPaneById(ProjectViewPane.ID) as AbstractProjectViewPSIPane
  val treeStructure = viewPane.createStructure()


  val paneId = viewPane.getId()
  val comp = GroupByTypeComparator(projectView, paneId)
  val openDirs = HashMap<SmartPsiElementPointer<PsiDirectory>, Path>()
  val ptrManager = SmartPointerManager.getInstance(project)

  companion object {
    private val LOG = Logger.getInstance("#com.jetbrains.reactiveidea.ProjectViewHost")
  }

  init {

    init += {
      val root = treeStructure.getRootElement();
      val descriptor = treeStructure.createDescriptor(root, null) as AbstractTreeNode<*>
      val current = path / paneId
      val rootNode = createNode(descriptor, lifetime, current, 0)
      current.putIn(it, MapModel(mapOf(root.toString() to rootNode)))
    }

    val psiTreeChangeListener = object : PsiTreeChangeAdapter() {
      override fun childAdded(event: PsiTreeChangeEvent) {
        handle(event)
      }

      override fun childReplaced(event: PsiTreeChangeEvent) {
        handle(event)
      }

      override fun childMoved(event: PsiTreeChangeEvent) {
        handle(event)
      }

      override fun childRemoved(event: PsiTreeChangeEvent) {
        handle(event)
      }

      override fun propertyChanged(event: PsiTreeChangeEvent) {
        handle(event)
      }

      fun handle(event: PsiTreeChangeEvent) {
        var parent = event.getParent()
        if (parent !is PsiDirectory) {
          parent = event.getFile()?.getParent()
        }
        if (parent is PsiDirectory) {
          updateDir(parent)
        }
      }

      fun updateDir(dir: PsiDirectory) {
        val path = openDirs[ptrManager.createSmartPsiElementPointer(dir)]
        if (path != null) {
          reactiveModel.transaction { m ->
            val model = path.getIn(m)
            if (model == null) {
              LOG.error("Unexpected model behaviour. Path $path null in model. Directory: ${dir.toString()}")
              ApplicationManagerEx.getApplicationEx().assertIsDispatchThread()
              return@transaction m
            }
            val host = model.meta.host<NodeHost>()
            updateChilds(m, host.descriptor, path.dropLast(1), host.index)
          }
        }
      }
    }
    PsiManager.getInstance(project).addPsiTreeChangeListener(psiTreeChangeListener)
  }

  class LeafHost(val psi: SmartPsiElementPointer<PsiElement>)
  class NodeHost(val descriptor: AbstractTreeNode<*>, val index: Int)

  private fun createNode(descriptor: AbstractTreeNode<*>, parentLifetime: Lifetime, path: Path, index: Int): Model {
    descriptor.update();
    val map = HashMap<String, Model>()
    val value = descriptor.getValue()
    val state = if (value is PsiFileSystemItem && value.isDirectory() || descriptor.getChildren().isNotEmpty()) "closed" else "leaf"
    val meta =
        if (state != "leaf") createMeta(
            "host", NodeHost(descriptor, index),
            "lifetime", Lifetime.create(parentLifetime).lifetime)
        else if (value is PsiElement) createMeta("host", LeafHost(ptrManager.createSmartPsiElementPointer(value)))
        else emptyMeta()

    map.put("state", PrimitiveModel(state))
    map.put("order", PrimitiveModel(index))
    map.put("childs", MapModel())

    if (state != "leaf") {
      val stateSignal = reactiveModel.subscribe(meta.lifetime()!!, path / descriptor.toString())
      reaction(true, "update state of project tree node", stateSignal) { state ->
        if (state != null) {
          state as MapModel
          val openState = (state["state"] as PrimitiveModel<*>).value
          if (openState == "open" && (state["childs"] == null || (state["childs"] as MapModel).isEmpty())) {
            reactiveModel.transaction { m ->
              updateChilds(m, descriptor, path, index)
            }
          }
        }
      }
    }
    return MapModel(map, meta)
  }

  private fun updateChilds(m: MapModel, descriptor: AbstractTreeNode<*>, path: Path, index: Int): MapModel {
    descriptor.update();
    val parentPath = path / descriptor.toString()
    val parentLifetime = parentPath.getIn(m)!!.meta.lifetime()!!
    val nodesPath = parentPath / "childs"
    val childsLifetime = Lifetime.create(parentLifetime).lifetime
    if (descriptor.getValue() is PsiDirectory) {
      val ptr = ptrManager.createSmartPsiElementPointer(descriptor.getValue() as PsiDirectory)
      openDirs[ptr] = parentPath
      childsLifetime += {
        openDirs.remove(ptr)
      }
    }
    val children = HashMap<String, Model>()
    treeStructure.getChildElements(descriptor)
        .map { child -> treeStructure.createDescriptor(child, null) as AbstractTreeNode<*> }
        .filter { descr -> descr.update(); true }
        .sortBy(comp)
        .forEachIndexed { idx, descr ->
          var name = StringUtil.notNullize(descr.toString(), "null")
          children[name] = createNode(descr, parentLifetime, nodesPath, idx)
        }
    return nodesPath.putIn(m, MapModel(children, createMeta("lifetime", childsLifetime)))
  }
}
