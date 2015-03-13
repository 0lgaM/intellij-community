/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package com.intellij.tasks;

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Date;

/**
 * @author Dmitry Avdeev
 */
public abstract class Task {

  public static Task[] EMPTY_ARRAY = new Task[0];

  /**
   * Semi-unique task identifier, that helps user to identify issue, e.g. IDEA-00001. It's important that its format is consistent with
   * {@link TaskRepository#extractId(String)}, because otherwise task won't be updated on its activation.
   *
   * Note that it's not necessarily unique <i>across all projects on the server (if any)</i>, it's sole purpose is to be presented in UI.
   * Use {@link #getGlobalId()} to return globally unique ID of the task.
   *
   * @return presentable ID as described
   *
   * @see com.intellij.tasks.TaskRepository#extractId(String)
   * @see com.intellij.tasks.TaskManager#activateTask(Task, boolean)
   */
  @NotNull
  public abstract String getId();

  /**
   * Difference between ID and global ID is that the latter one is used to uniquely identify task on remote server, аt the same time usual
   * ID returned by {@link #getId()} is not required to be unique and can be more readable and thus is shown in UI.
   * However these IDs are not necessarily different e.g request ID of type ABC-123 in YouTrack can be treated both as ID (presentable,
   * local) and global ID that can be used to unambiguously locate request on server.
   * By default this method returns value of {@link #getId()}.
   *
   * For example Gitlab uses two different types of ID for this purpose <tt>id</tt> is global ID, it's number bound to the issue that is
   * unique across all projects, and <tt>iid</tt> is per-project ID, it's shorter and more convenient for user to see.
   *
   * Another example is Trello. In its case global ID is 96-bit hash value, and normal ID is short incrementing number associated with
   * card's board. You also can think about them as local and global changeset's IDs in Mercurial.
   *
   * @return unique global ID as described
   * @since 14.1
   */
  @NotNull
  public String getGlobalId() {
    return getId();
  }

  @NotNull
  public TaskCoordinates getCoordinates() {
    final TaskRepository repository = getRepository();
    if (repository == null) {
      return new TaskCoordinates(isIssue() ? "" : TaskCoordinates.LOCAL_REPOSITORY_TYPE, "", getGlobalId());
    }
    return new TaskCoordinates(repository.getRepositoryType().getName(), repository.getUrl(), getGlobalId());
  }

  /**
   * Short task description.
   * @return description
   */
  @NotNull
  public abstract String getSummary();

  @Nullable
  public abstract String getDescription();

  @NotNull
  public abstract Comment[] getComments();

  @NotNull
  public abstract Icon getIcon();

  @NotNull
  public abstract TaskType getType();

  @Nullable
  public abstract Date getUpdated();

  @Nullable
  public abstract Date getCreated();

  public abstract boolean isClosed();

  @Nullable
  public String getCustomIcon() {
    return null;
  }

  /**
   * @return true if bugtracking issue is associated
   */
  public abstract boolean isIssue();

  /**
   * Means that this task was created from issue/request of {@link TaskRepository}. Otherwise it's probably {@link LocalTask} not bound to any issue.
   */
  @Nullable
  public abstract String getIssueUrl();

  /**
   * @return null if no issue is associated
   * @see #isIssue()
   */
  @Nullable
  public TaskRepository getRepository() {
    return null;
  }

  @Nullable
  public TaskState getState() {
    return null;
  }

  @Override
  public final String toString() {
    String text;
    if (isIssue()) {
      text = getId() + ": " + getSummary();
    } else {
      text = getSummary();
    }
    return StringUtil.first(text, 60, true);
  }

  public String getPresentableName() {
    return toString();
  }

  @Override
  public final boolean equals(Object obj) {
    return obj instanceof Task && ((Task)obj).getGlobalId().equals(getGlobalId());
  }

  @Override
  public final int hashCode() {
    return getGlobalId().hashCode();
  }

  /**
   * <b>Per-project</b> issue identifier. Default behavior is to extract project name from task's ID.
   * If your service doesn't provide issue ID in format <tt>PROJECT-123</tt> be sure to initialize it manually,
   * as it will be used to format commit messages.
   *
   * @return project-wide issue identifier
   *
   * @see #getId()
   * @see TaskRepository#getCommitMessageFormat()
   */
  @NotNull
  public String getNumber() {
    return extractNumberFromId(getId());
  }

  @NotNull
  protected static String extractNumberFromId(@NotNull String id) {
    int i = id.lastIndexOf('-');
    return i > 0 ? id.substring(i + 1) : id;
  }

  /**
   * Name of the project task belongs to. Default behavior is to extract project name from task's ID.
   * If your service doesn't provide issue ID in format <tt>PROJECT-123</tt> be sure to initialize it manually,
   * as it will be used to format commit messages.
   *
   * @return name of the project
   *
   * @see #getId()
   * @see TaskRepository#getCommitMessageFormat()
   */
  @Nullable
  public String getProject() {
    return extractProjectFromId(getId());
  }

  @Nullable
  protected static String extractProjectFromId(@NotNull String id) {
    int i = id.lastIndexOf('-');
    return i > 0 ? id.substring(0, i) : null;
  }
}
