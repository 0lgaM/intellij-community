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

import com.github.krukow.clj_ds.PersistentMap
import com.github.krukow.clj_lang.PersistentHashMap
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.ApplicationComponent
import com.intellij.openapi.editor.actionSystem.DocCommandGroupId
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerAdapter
import com.intellij.pom.Navigatable
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.usages.Usage
import com.intellij.util.ui.EdtInvocationManager
import com.intellij.util.ui.UIUtil
import com.jetbrains.reactiveidea.history.host.HistoryHost
import com.jetbrains.reactiveidea.history.host.historyPath
import com.jetbrains.reactiveidea.usages.UsagesHost
import com.jetbrains.reactivemodel.*
import com.jetbrains.reactivemodel.models.ListModel
import com.jetbrains.reactivemodel.models.MapModel
import com.jetbrains.reactivemodel.models.PrimitiveModel
import com.jetbrains.reactivemodel.models.toPath
import com.jetbrains.reactivemodel.util.Lifetime
import com.jetbrains.reactivemodel.util.get
import com.jetbrains.reactivemodel.util.host

public class ReactiveModelsManager() : ApplicationComponent {
  val lifetime = Lifetime.create(Lifetime.Eternal)
  // TODO need to think about synchronization
  val reactiveModels: VariableSignal<PersistentMap<String, ReactiveModel>>
      = VariableSignal(lifetime, "reactive models", PersistentHashMap.emptyMap<String, ReactiveModel>())

  public fun modelsForProject(project: Project): Signal<List<ReactiveModel>> =
      reaction(true, "models for project ${project.getName()}", reactiveModels) {
        it.values().filter {
          it.root.meta.host<ProjectHost?>()?.project == project
        }
      }

  override fun getComponentName(): String = "ReactiveModelsManager"

  override fun initComponent() {
    serverModel(lifetime.lifetime, 12346, reactiveModels) { reactiveModel ->
      UIUtil.invokeLaterIfNeeded {
        reactiveModel.registerHandler(lifetime.lifetime, "invoke-action") { args: MapModel, model ->
          val actionName = (args["name"] as PrimitiveModel<*>).value as String
          val contextPath = (args["context"] as ListModel).toPath()
          val anAction = ActionManager.getInstance().getAction(actionName)
          if (anAction != null) {
            val dataContext = ServerDataManagerImpl.getInstance().getDataContext(contextPath, reactiveModel)
            EdtInvocationManager.getInstance().invokeLater {
              anAction.actionPerformed(AnActionEvent.createFromDataContext("ide-frontend", Presentation(), dataContext))
            }
          } else {
            println("can't find idea action $args")
          }
          model
        }

        val handlers = hashMapOf(
            "open-file" to { host: ProjectViewHost.LeafHost ->
              val psi = host.psi
              FileEditorManager.getInstance(psi.getProject()).openFile(psi.getVirtualFile(), true)
            },
            "go-usage" to { host: UsagesHost.UsageHost ->
              val node = host.node
              if (node is Navigatable) {
                node.navigate(true)
              }
            })

        handlers.forEach { e: Map.Entry<String, Any> ->
          val name = e.getKey()
          val func = e.getValue() as (Host) -> Unit
          reactiveModel.registerHandler(lifetime.lifetime, name) { args: MapModel, model ->
            val path = (args["path"] as ListModel).toPath()
            val host = path.getIn(model)!!.meta.host<Host?>()
            if (host != null) {
              EdtInvocationManager.getInstance().invokeLater {
                func(host)
              }
            }
            model
          }
        }

        reactiveModel.registerHandler(lifetime.lifetime, "type-a") { args: MapModel, model ->
          val path = (args["path"] as ListModel).toPath()
          val mapModel = path.getIn(model) as MapModel
          val editorHost = mapModel.meta.host<EditorHost>()
          val actionManager = EditorActionManager.getInstance()

          CommandProcessor.getInstance().executeCommand(editorHost.editor.getProject(), object : Runnable {
            override fun run() {
              CommandProcessor.getInstance().setCurrentCommandGroupId(editorHost.editor.getDocument())
              val dataContext = ServerDataManagerImpl.getInstance().getDataContext(path, reactiveModel)
              ActionManagerEx.getInstanceEx().fireBeforeEditorTyping('a', dataContext)
              actionManager.getTypedAction().actionPerformed(editorHost.editor, 'a', dataContext)
            }
          }, null, DocCommandGroupId.noneGroupId(editorHost.editor.getDocument()))

          model
        }

        for (project in ProjectManager.getInstance().getOpenProjects()) {
          reactiveModel.host(Path(), { path, lifetime, initializer ->
            ProjectHost(path, lifetime, initializer, project, reactiveModel)
          })
        }

        ProjectManager.getInstance().addProjectManagerListener(object : ProjectManagerAdapter() {
          override fun projectOpened(project: Project) {
            reactiveModel.host(Path(), { path, lifetime, initializer ->
              ProjectHost(path, lifetime, initializer, project, reactiveModel)
            })
          }
        })
      }
    }
  }

  override fun disposeComponent() {
    lifetime.terminate()
  }
}
