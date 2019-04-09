// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.impl.projectlevelman;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.BackgroundTaskUtil;
import com.intellij.openapi.project.DumbAwareRunnable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.impl.DefaultVcsRootPolicy;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsInitObject;
import com.intellij.openapi.vcs.ui.VcsBalloonProblemNotifier;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerListener;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.util.Alarm;
import com.intellij.util.Functions;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.*;

import static com.intellij.util.containers.ContainerUtil.*;
import static java.util.Collections.unmodifiableList;

public class NewMappings {
  private static final Comparator<MappedRoot> ROOT_COMPARATOR = Comparator.comparingInt(it -> -it.root.getPath().length());
  private static final Comparator<VcsDirectoryMapping> MAPPINGS_COMPARATOR = Comparator.comparing(VcsDirectoryMapping::getDirectory);

  private final static Logger LOG = Logger.getInstance("#com.intellij.openapi.vcs.impl.projectlevelman.NewMappings");
  private final Object myUpdateLock = new Object();

  private FileWatchRequestsManager myFileWatchRequestsManager;

  private final DefaultVcsRootPolicy myDefaultVcsRootPolicy;
  private final ProjectLevelVcsManager myVcsManager;
  private final FileStatusManager myFileStatusManager;
  private final Project myProject;

  @NotNull private Disposable myFilePointerDisposable = Disposer.newDisposable();
  private volatile List<VcsDirectoryMapping> myMappings = Collections.emptyList(); // sorted by MAPPINGS_COMPARATOR
  private volatile List<MappedRoot> myMappedRoots = Collections.emptyList(); // sorted by ROOT_COMPARATOR
  private volatile List<AbstractVcs> myActiveVcses = Collections.emptyList();
  private boolean myActivated = false;

  @NotNull private final MergingUpdateQueue myRootUpdateQueue;
  private final VirtualFilePointerListener myFilePointerListener;

  public NewMappings(Project project,
                     ProjectLevelVcsManagerImpl vcsManager,
                     FileStatusManager fileStatusManager,
                     DefaultVcsRootPolicy defaultVcsRootPolicy) {
    myProject = project;
    myVcsManager = vcsManager;
    myFileStatusManager = fileStatusManager;
    myFileWatchRequestsManager = new FileWatchRequestsManager(myProject, this, LocalFileSystem.getInstance());
    myDefaultVcsRootPolicy = defaultVcsRootPolicy;

    myRootUpdateQueue = new MergingUpdateQueue("NewMappings", 1000, true, null, project, null, Alarm.ThreadToUse.POOLED_THREAD);

    vcsManager.addInitializationRequest(VcsInitObject.MAPPINGS, (DumbAwareRunnable)() -> {
      if (!myProject.isDisposed()) {
        activateActiveVcses();
      }
    });

    myFilePointerListener = new VirtualFilePointerListener() {
      @Override
      public void validityChanged(@NotNull VirtualFilePointer[] pointers) {
        scheduleMappedRootsUpdate();
      }
    };
  }

  @TestOnly
  public void setFileWatchRequestsManager(FileWatchRequestsManager fileWatchRequestsManager) {
    assert ApplicationManager.getApplication().isUnitTestMode();
    myFileWatchRequestsManager = fileWatchRequestsManager;
  }

  public AbstractVcs[] getActiveVcses() {
    return myActiveVcses.toArray(new AbstractVcs[0]);
  }

  public boolean hasActiveVcss() {
    return !myActiveVcses.isEmpty();
  }

  public void activateActiveVcses() {
    synchronized (myUpdateLock) {
      if (myActivated) return;
      myActivated = true;

      updateActiveVcses();
    }

    mappingsChanged();
  }

  public void setMapping(@NotNull String path, @Nullable String activeVcsName) {
    LOG.debug("setMapping path = '" + path + "' vcs = " + activeVcsName);
    final VcsDirectoryMapping newMapping = new VcsDirectoryMapping(path, activeVcsName);

    List<VcsDirectoryMapping> newMappings = new ArrayList<>(myMappings);
    newMappings.removeIf(mapping -> Comparing.equal(mapping.systemIndependentPath(), newMapping.systemIndependentPath()));
    newMappings.add(newMapping);

    updateVcsMappings(newMappings);
  }

  @TestOnly
  public void waitMappedRootsUpdate() {
    myRootUpdateQueue.flush();
  }

  public void scheduleMappedRootsUpdate() {
    myRootUpdateQueue.queue(new Update("update") {
      @Override
      public void run() {
        updateVcsMappings(null);
      }
    });
  }

  /**
   * @param mappings New mappings or null, if only mapped roots should be updated
   */
  private void updateVcsMappings(@Nullable Collection<? extends VcsDirectoryMapping> mappings) {
    myRootUpdateQueue.cancelAllUpdates();

    List<VcsDirectoryMapping> newMappings = mappings != null ? unmodifiableList(sorted(removeDuplicates(mappings), MAPPINGS_COMPARATOR))
                                                             : myMappings;

    boolean mappingsChanged = !Comparing.equal(myMappings, newMappings);
    if (mappings != null && !mappingsChanged) return; // mappings are up-to-date

    Mappings newMappedRoots = collectMappedRoots(newMappings);

    synchronized (myUpdateLock) {
      Disposer.dispose(myFilePointerDisposable);
      myMappings = newMappings;
      myMappedRoots = newMappedRoots.mappedRoots;
      myFilePointerDisposable = newMappedRoots.filePointerDisposable;

      if (myActivated) {
        updateActiveVcses();
      }

      if (mappingsChanged || LOG.isDebugEnabled()) {
        dumpMappingsToLog();
      }
    }

    mappingsChanged();
  }

  @NotNull
  private static List<VcsDirectoryMapping> removeDuplicates(@NotNull Collection<? extends VcsDirectoryMapping> mappings) {
    List<VcsDirectoryMapping> newMapping = new ArrayList<>();
    Set<String> paths = new HashSet<>();

    for (VcsDirectoryMapping mapping : reverse(newArrayList(mappings))) {
      if (paths.add(mapping.systemIndependentPath())) {
        newMapping.add(mapping);
      }
    }
    return newMapping;
  }

  @NotNull
  private Mappings collectMappedRoots(@NotNull List<VcsDirectoryMapping> mappings) {
    AllVcsesI allVcsesI = AllVcses.getInstance(myProject);
    VirtualFilePointerManager pointerManager = VirtualFilePointerManager.getInstance();

    Map<VirtualFile, MappedRoot> mappedRoots = new HashMap<>();
    Disposable pointerDisposable = Disposer.newDisposable();

    // direct mappings have priority over <Project> mappings
    for (VcsDirectoryMapping mapping : mappings) {
      if (mapping.isDefaultMapping()) continue;
      AbstractVcs vcs = getMappingsVcs(mapping, allVcsesI);
      String rootPath = mapping.getDirectory();

      ReadAction.run(() -> {
        VirtualFile vcsRoot = LocalFileSystem.getInstance().findFileByPath(rootPath);

        if (vcsRoot != null && vcsRoot.isDirectory()) {
          if (checkMappedRoot(vcs, vcsRoot)) {
            mappedRoots.putIfAbsent(vcsRoot, new MappedRoot(vcs, mapping, vcsRoot));
          }
          else {
            mappedRoots.putIfAbsent(vcsRoot, new MappedRoot(null, mapping, vcsRoot));
          }
        }

        pointerManager.create(VfsUtilCore.pathToUrl(rootPath), pointerDisposable, myFilePointerListener);
      });
    }

    for (VcsDirectoryMapping mapping : mappings) {
      if (!mapping.isDefaultMapping()) continue;
      AbstractVcs<?> vcs = getMappingsVcs(mapping, allVcsesI);
      if (vcs == null) continue;

      List<VirtualFile> defaultRoots = detectDefaultRootsFor(vcs, myDefaultVcsRootPolicy.getDefaultVcsRoots());

      ReadAction.run(() -> {
        for (VirtualFile vcsRoot : defaultRoots) {
          if (vcsRoot != null && vcsRoot.isDirectory()) {
            mappedRoots.putIfAbsent(vcsRoot, new MappedRoot(vcs, mapping, vcsRoot));

            pointerManager.create(vcsRoot, pointerDisposable, myFilePointerListener);
          }
        }
      });
    }

    return new Mappings(unmodifiableList(sorted(mappedRoots.values(), ROOT_COMPARATOR)), pointerDisposable);
  }

  @Nullable
  private static AbstractVcs getMappingsVcs(@NotNull VcsDirectoryMapping mapping, @NotNull AllVcsesI allVcsesI) {
    String vcsName = mapping.getVcs();
    return vcsName != null ? allVcsesI.getByName(vcsName) : null;
  }

  private boolean checkMappedRoot(@Nullable AbstractVcs vcs, @NotNull VirtualFile vcsRoot) {
    if (vcs == null) return false;
    VcsRootChecker rootChecker = myVcsManager.getRootChecker(vcs);
    return rootChecker.validateRoot(vcsRoot.getPath());
  }

  @NotNull
  private List<VirtualFile> detectDefaultRootsFor(@NotNull AbstractVcs<?> vcs, @NotNull Collection<VirtualFile> files) {
    VcsRootChecker rootChecker = myVcsManager.getRootChecker(vcs);
    HashSet<VirtualFile> checkedDirs = new HashSet<>();

    List<VirtualFile> gitRoots = new ArrayList<>();
    for (VirtualFile f : files) {
      while (f != null) {
        ProgressManager.checkCanceled();
        if (!checkedDirs.add(f)) break;
        if (rootChecker.isRoot(f.getPath())) {
          gitRoots.add(f);
          break;
        }
        f = f.getParent();
      }
    }
    return gitRoots;
  }

  public void mappingsChanged() {
    BackgroundTaskUtil.syncPublisher(myProject, ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED).directoryMappingChanged();
    myFileStatusManager.fileStatusesChanged();
    myFileWatchRequestsManager.ping();
  }

  private void dumpMappingsToLog() {
    for (VcsDirectoryMapping mapping : myMappings) {
      String path = mapping.isDefaultMapping() ? VcsDirectoryMapping.PROJECT_CONSTANT : mapping.getDirectory();
      String vcs = mapping.getVcs();
      LOG.info(String.format("VCS Root: [%s] - [%s]", vcs, path));
    }

    if (LOG.isDebugEnabled()) {
      for (MappedRoot root : myMappedRoots) {
        LOG.debug(String.format("Mapped Root: [%s] - [%s]", root.vcs, root.root.getPath()));
      }
    }
  }

  public void setDirectoryMappings(@NotNull List<? extends VcsDirectoryMapping> items) {
    LOG.debug("setDirectoryMappings, size: " + items.size());

    updateVcsMappings(items);
  }


  @Nullable
  public MappedRoot getMappedRootFor(@Nullable VirtualFile file) {
    if (file == null || !file.isInLocalFileSystem()) return null;
    if (myVcsManager.isIgnored(file)) return null;

    final List<MappedRoot> mappings = new ArrayList<>(myMappedRoots);

    // ROOT_COMPARATOR ensures we'll find "inner" matching root before "outer" one
    for (MappedRoot mapping : mappings) {
      if (mapping.root.isValid() && VfsUtilCore.isAncestor(mapping.root, file, false)) {
        return mapping;
      }
    }
    return null;
  }

  @Nullable
  public MappedRoot getMappedRootFor(@Nullable FilePath filePath) {
    if (filePath == null || filePath.isNonLocal()) return null;
    if (myVcsManager.isIgnored(filePath)) return null;

    final List<MappedRoot> mappings = new ArrayList<>(myMappedRoots);

    // ROOT_COMPARATOR ensures we'll find "inner" matching root before "outer" one
    for (MappedRoot mapping : mappings) {
      if (mapping.root.isValid() && FileUtil.startsWith(filePath.getPath(), mapping.root.getPath())) {
        return mapping;
      }
    }
    return null;
  }

  @NotNull
  public List<VirtualFile> getMappingsAsFilesUnderVcs(@NotNull AbstractVcs vcs) {
    return mapNotNull(myMappedRoots, root -> {
      return vcs.equals(root.vcs) ? root.root : null;
    });
  }

  public void disposeMe() {
    LOG.debug("dispose me");

    synchronized (myUpdateLock) {
      Disposer.dispose(myFilePointerDisposable);
      myMappings = Collections.emptyList();
      myMappedRoots = Collections.emptyList();
      myFilePointerDisposable = Disposer.newDisposable();
      updateActiveVcses();
    }
    myFileWatchRequestsManager.ping();
  }

  public List<VcsDirectoryMapping> getDirectoryMappings() {
    return myMappings;
  }

  public List<VcsDirectoryMapping> getDirectoryMappings(String vcsName) {
    return filter(myMappings, mapping -> Comparing.equal(mapping.getVcs(), vcsName));
  }

  @Nullable
  public String haveDefaultMapping() {
    VcsDirectoryMapping defaultMapping = find(myMappings, mapping -> mapping.isDefaultMapping());
    return defaultMapping != null ? defaultMapping.getVcs() : null;
  }

  public boolean isEmpty() {
    return all(myMappings, mapping -> StringUtil.isEmpty(mapping.getVcs()));
  }

  public void removeDirectoryMapping(@NotNull VcsDirectoryMapping mapping) {
    LOG.debug("remove mapping: " + mapping.getDirectory());

    List<VcsDirectoryMapping> newMappings = new ArrayList<>(myMappings);
    newMappings.remove(mapping);

    updateVcsMappings(newMappings);
  }

  public void cleanupMappings() {
    LocalFileSystem lfs = LocalFileSystem.getInstance();

    List<VcsDirectoryMapping> oldMappings = new ArrayList<>(getDirectoryMappings());

    List<VcsDirectoryMapping> filteredMappings = new ArrayList<>();

    VcsDirectoryMapping defaultMapping = find(oldMappings, it -> it.isDefaultMapping());
    if (defaultMapping != null) {
      oldMappings.remove(defaultMapping);
      filteredMappings.add(defaultMapping);
    }

    MultiMap<String, VcsDirectoryMapping> groupedMappings = groupBy(oldMappings, mapping -> mapping.getVcs());
    for (Map.Entry<String, Collection<VcsDirectoryMapping>> entry : groupedMappings.entrySet()) {
      String vcsName = entry.getKey();
      Collection<VcsDirectoryMapping> mappings = entry.getValue();

      List<Pair<VirtualFile, VcsDirectoryMapping>> objects = mapNotNull(mappings, dm -> {
        VirtualFile vf = lfs.refreshAndFindFileByPath(dm.getDirectory());
        return vf == null ? null : Pair.create(vf, dm);
      });

      if (StringUtil.isEmptyOrSpaces(vcsName)) {
        filteredMappings.addAll(map(objects, Functions.pairSecond()));
      }
      else {
        AbstractVcs<?> vcs = myVcsManager.findVcsByName(vcsName);
        if (vcs == null) {
          VcsBalloonProblemNotifier.showOverChangesView(myProject, "VCS plugin not found for mapping to : '" + vcsName + "'",
                                                        MessageType.ERROR);
          filteredMappings.addAll(mappings);
        }
        else {
          filteredMappings.addAll(map(vcs.filterUniqueRoots(objects, pair -> pair.getFirst()), Functions.pairSecond()));
        }
      }
    }

    updateVcsMappings(filteredMappings);
  }

  private void updateActiveVcses() {
    List<AbstractVcs> oldVcses = myActiveVcses;
    myActiveVcses = unmodifiableList(new ArrayList<>(map2SetNotNull(myMappedRoots, root -> root.vcs)));

    Collection<AbstractVcs> toAdd = subtract(myActiveVcses, oldVcses);
    Collection<AbstractVcs> toRemove = subtract(oldVcses, myActiveVcses);

    for (AbstractVcs vcs : toAdd) {
      try {
        vcs.doActivate();
      }
      catch (VcsException e) {
        LOG.error(e);
      }
    }
    for (AbstractVcs vcs : toRemove) {
      try {
        vcs.doDeactivate();
      }
      catch (VcsException e) {
        LOG.error(e);
      }
    }
  }

  public boolean haveActiveVcs(final String name) {
    return exists(myActiveVcses, vcs -> Comparing.equal(vcs.getName(), name));
  }

  public void beingUnregistered(final String name) {
    List<VcsDirectoryMapping> newMappings = new ArrayList<>(myMappings);
    newMappings.removeIf(mapping -> Comparing.equal(mapping.getVcs(), name));

    updateVcsMappings(newMappings);
  }

  public static class MappedRoot {
    @Nullable public final AbstractVcs vcs;
    @NotNull public final VcsDirectoryMapping mapping;
    @NotNull public final VirtualFile root;

    private MappedRoot(@Nullable AbstractVcs vcs, @NotNull VcsDirectoryMapping mapping, @NotNull VirtualFile root) {
      this.vcs = vcs;
      this.mapping = mapping;
      this.root = root;
    }
  }

  private static class Mappings {
    @NotNull public final List<MappedRoot> mappedRoots;
    @NotNull public final Disposable filePointerDisposable;

    private Mappings(@NotNull List<MappedRoot> mappedRoots, @NotNull Disposable filePointerDisposable) {
      this.mappedRoots = mappedRoots;
      this.filePointerDisposable = filePointerDisposable;
    }
  }
}
