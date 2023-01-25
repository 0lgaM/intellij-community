// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui.details.model

import com.intellij.collaboration.async.modelFlow
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.childScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformLatest
import org.jetbrains.plugins.gitlab.api.GitLabProjectConnection
import org.jetbrains.plugins.gitlab.api.getResultOrThrow
import org.jetbrains.plugins.gitlab.mergerequest.api.request.loadMergeRequest
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequestId
import org.jetbrains.plugins.gitlab.mergerequest.data.LoadedGitLabMergeRequest
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabMergeRequestDetailsLoadingViewModel.LoadingState

private val LOG = logger<GitLabMergeRequestDetailsLoadingViewModel>()

internal interface GitLabMergeRequestDetailsLoadingViewModel {
  val mergeRequestLoadingFlow: Flow<LoadingState>

  fun requestLoad()

  sealed interface LoadingState {
    object Loading : LoadingState
    class Error(val exception: Throwable) : LoadingState
    class Result(val detailsVm: GitLabMergeRequestDetailsViewModel) : LoadingState
  }
}

internal class GitLabMergeRequestDetailsLoadingViewModelImpl(
  parentScope: CoroutineScope,
  connection: GitLabProjectConnection,
  private val mergeRequestId: GitLabMergeRequestId
) : GitLabMergeRequestDetailsLoadingViewModel {
  private val scope = parentScope.childScope(Dispatchers.Default)
  private val api = connection.apiClient
  private val project = connection.repo.repository

  private val loadingRequests = MutableSharedFlow<Unit>(1)

  @OptIn(ExperimentalCoroutinesApi::class)
  override val mergeRequestLoadingFlow: Flow<LoadingState> = loadingRequests.transformLatest {
    emit(LoadingState.Loading)

    coroutineScope {
      val result = try {
        val data = scope.async(Dispatchers.IO) {
          api.loadMergeRequest(project, mergeRequestId).getResultOrThrow()
        }
        val mergeRequest = LoadedGitLabMergeRequest(data.await())
        val detailsVm = GitLabMergeRequestDetailsViewModelImpl(mergeRequest)
        LoadingState.Result(detailsVm)
      }
      catch (ce: CancellationException) {
        throw ce
      }
      catch (e: Exception) {
        LoadingState.Error(e)
      }
      emit(result)
      awaitCancellation()
    }
  }.modelFlow(scope, LOG)

  override fun requestLoad() {
    scope.launch {
      loadingRequests.emit(Unit)
    }
  }
}