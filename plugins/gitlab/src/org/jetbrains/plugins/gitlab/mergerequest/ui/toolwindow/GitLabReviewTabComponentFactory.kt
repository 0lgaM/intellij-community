// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.toolwindow

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.CollaborationToolsUIUtil.isDefault
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.collaboration.ui.toolwindow.ReviewTabsComponentFactory
import com.intellij.collaboration.ui.util.bindDisabled
import com.intellij.collaboration.ui.util.bindVisibility
import com.intellij.collaboration.util.URIUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import git4idea.remote.hosting.ui.RepositoryAndAccountSelectorComponentFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import org.jetbrains.plugins.gitlab.GitLabProjectsManager
import org.jetbrains.plugins.gitlab.api.GitLabApiManager
import org.jetbrains.plugins.gitlab.api.GitLabProjectConnectionManager
import org.jetbrains.plugins.gitlab.api.GitLabProjectCoordinates
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO
import org.jetbrains.plugins.gitlab.authentication.GitLabLoginUtil
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager
import org.jetbrains.plugins.gitlab.authentication.ui.GitLabAccountsDetailsProvider
import org.jetbrains.plugins.gitlab.mergerequest.action.GitLabMergeRequestsActionKeys
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequestId
import org.jetbrains.plugins.gitlab.mergerequest.diff.GitLabMergeRequestDiffModelRepository
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.GitLabMergeRequestDetailsComponentFactory
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabMergeRequestDetailsLoadingViewModel
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabMergeRequestDetailsLoadingViewModelImpl
import org.jetbrains.plugins.gitlab.mergerequest.ui.filters.GitLabMergeRequestsFiltersHistoryModel
import org.jetbrains.plugins.gitlab.mergerequest.ui.filters.GitLabMergeRequestsFiltersViewModel
import org.jetbrains.plugins.gitlab.mergerequest.ui.filters.GitLabMergeRequestsFiltersViewModelImpl
import org.jetbrains.plugins.gitlab.mergerequest.ui.filters.GitLabMergeRequestsPersistentFiltersHistory
import org.jetbrains.plugins.gitlab.mergerequest.ui.list.GitLabMergeRequestsListViewModel
import org.jetbrains.plugins.gitlab.mergerequest.ui.list.GitLabMergeRequestsListViewModelImpl
import org.jetbrains.plugins.gitlab.mergerequest.ui.list.GitLabMergeRequestsPanelFactory
import org.jetbrains.plugins.gitlab.util.GitLabBundle
import org.jetbrains.plugins.gitlab.util.GitLabProjectMapping
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.*

internal class GitLabReviewTabComponentFactory(private val project: Project) : ReviewTabsComponentFactory<GitLabReviewTab, GitLabToolwindowProjectContext> {
  private val projectsManager = project.service<GitLabProjectsManager>()
  private val accountManager = service<GitLabAccountManager>()
  private val connectionManager = project.service<GitLabProjectConnectionManager>()

  override fun createReviewListComponent(cs: CoroutineScope,
                                         projectContext: GitLabToolwindowProjectContext): JComponent {
    val connection = projectContext.connection
    val avatarIconsProvider: IconsProvider<GitLabUserDTO> = projectContext.avatarIconProvider

    val filterVm: GitLabMergeRequestsFiltersViewModel = GitLabMergeRequestsFiltersViewModelImpl(
      cs,
      currentUser = connection.currentUser,
      historyModel = GitLabMergeRequestsFiltersHistoryModel(GitLabMergeRequestsPersistentFiltersHistory()),
      avatarIconsProvider = avatarIconsProvider,
      projectData = connection.projectData
    )
    val listVm: GitLabMergeRequestsListViewModel = GitLabMergeRequestsListViewModelImpl(
      cs,
      filterVm = filterVm,
      repository = connection.repo.repository.projectPath.name,
      account = connection.account,
      avatarIconsProvider = avatarIconsProvider,
      accountManager = accountManager,
      tokenRefreshFlow = connection.tokenRefreshFlow,
      loaderSupplier = { filtersValue -> connection.projectData.mergeRequests.getListLoader(filtersValue.toSearchQuery()) }
    )
    return GitLabMergeRequestsPanelFactory().create(project, cs, listVm).also {
      DataManager.registerDataProvider(it) { dataId ->
        when {
          GitLabMergeRequestsActionKeys.FILES_CONTROLLER.`is`(dataId) -> projectContext.filesController
          else -> null
        }
      }
    }
  }

  override fun createTabComponent(cs: CoroutineScope,
                                  projectContext: GitLabToolwindowProjectContext,
                                  reviewTabType: GitLabReviewTab): JComponent {
    return when (reviewTabType) {
      is GitLabReviewTab.ReviewSelected -> {
        createReviewDetailsComponent(cs, projectContext, reviewTabType.reviewId)
      }
    }
  }

  override fun createEmptyTabContent(cs: CoroutineScope): JComponent {
    return createSelectorsComponent(cs)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun createReviewDetailsComponent(
    cs: CoroutineScope,
    projectContext: GitLabToolwindowProjectContext,
    reviewId: GitLabMergeRequestId
  ): JComponent {
    val conn = projectContext.connection
    val reviewDetailsVm = GitLabMergeRequestDetailsLoadingViewModelImpl(cs, conn.currentUser, conn.projectData, reviewId).apply {
      requestLoad()
    }

    val detailsVmFlow = reviewDetailsVm.mergeRequestLoadingFlow.mapLatest {
      (it as? GitLabMergeRequestDetailsLoadingViewModel.LoadingState.Result)?.detailsVm
    }.filterNotNull()

    cs.launch(Dispatchers.EDT, start = CoroutineStart.UNDISPATCHED) {
      detailsVmFlow.flatMapLatest {
        it.detailsInfoVm.showTimelineRequests
      }.collect {
        projectContext.filesController.openTimeline(reviewId, true)
      }
    }

    cs.launch(Dispatchers.EDT, start = CoroutineStart.UNDISPATCHED) {
      project.service<GitLabMergeRequestDiffModelRepository>().getShared(conn, reviewId).collectLatest {diffVm ->
        detailsVmFlow.flatMapLatest {
          it.changesVm.selectedChanges
        }.collectLatest {
          diffVm.setChanges(it)
        }
      }
    }

    cs.launch(Dispatchers.EDT, start = CoroutineStart.UNDISPATCHED) {
      detailsVmFlow.flatMapLatest {
        it.changesVm.showDiffRequests
      }.collect {
        projectContext.filesController.openDiff(reviewId, true)
      }
    }


    val avatarIconsProvider = projectContext.avatarIconProvider
    return GitLabMergeRequestDetailsComponentFactory.createDetailsComponent(project, cs, reviewDetailsVm, avatarIconsProvider).also {
      DataManager.registerDataProvider(it) { dataId ->
        when {
          GitLabMergeRequestsActionKeys.FILES_CONTROLLER.`is`(dataId) -> projectContext.filesController
          else -> null
        }
      }
    }
  }

  private fun createSelectorsComponent(cs: CoroutineScope): JComponent {
    // TODO: move vm creation to another place
    val selectorVm = GitLabRepositoryAndAccountSelectorViewModel(cs, projectsManager, accountManager, onSelected = { mapping, account ->
      withContext(cs.coroutineContext) {
        connectionManager.openConnection(mapping, account)
      }
    })

    val accountsDetailsProvider = GitLabAccountsDetailsProvider(cs) {
      // TODO: separate loader
      service<GitLabAccountManager>().findCredentials(it)?.let(service<GitLabApiManager>()::getClient)
    }

    val selectors = RepositoryAndAccountSelectorComponentFactory(selectorVm).create(
      scope = cs,
      repoNamer = { mapping ->
        val allProjects = selectorVm.repositoriesState.value.map { it.repository }
        getProjectDisplayName(allProjects, mapping.repository)
      },
      detailsProvider = accountsDetailsProvider,
      accountsPopupActionsSupplier = { createPopupLoginActions(selectorVm, it) },
      submitActionText = GitLabBundle.message("view.merge.requests.button"),
      loginButtons = createLoginButtons(cs, selectorVm),
      errorPresenter = GitLabSelectorErrorStatusPresenter(project, cs, selectorVm.accountManager) {
        selectorVm.submitSelection()
      }
    )

    cs.launch {
      selectorVm.loginRequestsFlow.collect { req ->
        val account = req.account
        if (account == null) {
          val (newAccount, token) = GitLabLoginUtil.logInViaToken(project, selectors, req.repo.repository.serverPath) { server, name ->
            req.accounts.none { it.server == server || it.name == name }
          } ?: return@collect
          req.login(newAccount, token)
        }
        else {
          val token = GitLabLoginUtil.updateToken(project, selectors, account) { server, name ->
            req.accounts.none { it.server == server || it.name == name }
          } ?: return@collect
          req.login(account, token)
        }
      }
    }

    return JPanel(BorderLayout()).apply {
      add(selectors, BorderLayout.NORTH)
    }
  }

  private fun createLoginButtons(scope: CoroutineScope, vm: GitLabRepositoryAndAccountSelectorViewModel)
    : List<JButton> {
    return listOf(
      JButton(CollaborationToolsBundle.message("login.button")).apply {
        isDefault = true
        isOpaque = false

        addActionListener {
          vm.requestTokenLogin(false, true)
        }

        bindDisabled(scope, vm.busyState)
        bindVisibility(scope, vm.tokenLoginAvailableState)
      }
    )
  }

  private fun createPopupLoginActions(vm: GitLabRepositoryAndAccountSelectorViewModel, mapping: GitLabProjectMapping?): List<Action> {
    if (mapping == null) return emptyList()
    return listOf(object : AbstractAction(CollaborationToolsBundle.message("login.button")) {
      override fun actionPerformed(e: ActionEvent?) {
        vm.requestTokenLogin(true, false)
      }
    })
  }

  private fun getProjectDisplayName(allProjects: List<GitLabProjectCoordinates>, project: GitLabProjectCoordinates): @NlsSafe String {
    val showServer = needToShowServer(allProjects)
    val builder = StringBuilder()
    if (showServer) builder.append(URIUtil.toStringWithoutScheme(project.serverPath.toURI())).append("/")
    builder.append(project.projectPath.owner).append("/")
    builder.append(project.projectPath.name)
    return builder.toString()
  }

  private fun needToShowServer(projects: List<GitLabProjectCoordinates>): Boolean {
    if (projects.size <= 1) return false
    val firstServer = projects.first().serverPath
    return projects.any { it.serverPath != firstServer }
  }
}
