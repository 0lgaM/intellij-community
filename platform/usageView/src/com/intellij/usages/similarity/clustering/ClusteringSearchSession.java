// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.usages.similarity.clustering;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.similarity.bag.Bag;
import com.intellij.usages.similarity.bag.BagsDistanceCalculator;
import com.intellij.usages.similarity.usageAdapter.SimilarUsage;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Does usage clustering during the find usage process. Clusters are used on find usages results presentation.
 */
public class ClusteringSearchSession {
  private final @NotNull List<UsageCluster> myClusters;
  private final double mySimilarityThreshold;

  public ClusteringSearchSession() {
    myClusters = new CopyOnWriteArrayList<>();
    mySimilarityThreshold = Registry.doubleValue("similarity.find.usages.groups.threshold");
  }

  public @NotNull List<UsageCluster> getClusters() {
    return myClusters;
  }

  @RequiresBackgroundThread
  public synchronized Usage clusterUsage(@NotNull Bag usageFeatures, @NotNull Usage similarUsageAdapter) {
    UsageCluster cluster = getTheMostSimilarCluster(usageFeatures);
    if (cluster == null) {
      cluster = createNewCluster();
    }
    cluster.addUsage((SimilarUsage)similarUsageAdapter);
    return similarUsageAdapter;
  }

  /**
   * This method is designed to use from {@link com.intellij.usages.UsageContextPanel#updateLayout(List)}
   */
  public @Nullable UsageCluster findCluster(@Nullable UsageInfo usageInfo) {
    for (UsageCluster cluster : myClusters) {
      for (SimilarUsage usage : cluster.getUsages()) {
        if (usage instanceof UsageInfo2UsageAdapter && ((UsageInfo2UsageAdapter)usage).getUsageInfo().equals(usageInfo)) {
          return cluster;
        }
      }
    }
    return null;
  }

  @RequiresBackgroundThread
  @RequiresReadLock
  public @NotNull List<UsageCluster> getClustersForSelectedUsages(@NotNull ProgressIndicator indicator, Set<Usage> selectedUsages) {
    //create new ArrayList from clusters to avoid a comparator contract violation during search
    return new ArrayList<>(getClusters()).stream()
      .sorted((o1, o2) -> {
        indicator.checkCanceled();
        return Integer.compare(o2.getOnlySelectedUsages(selectedUsages).size(), o1.getOnlySelectedUsages(selectedUsages).size());
      }).collect(Collectors.toList());
  }


  private @NotNull UsageCluster createNewCluster() {
    final UsageCluster newCluster = new UsageCluster(myClusters.size());
    myClusters.add(newCluster);
    return newCluster;
  }

  private @Nullable UsageCluster getTheMostSimilarCluster(@NotNull Bag features) {
    if (myClusters.size() == 0) {
      return null;
    }
    UsageCluster mostUsageCluster = null;
    double maxSimilarity = Double.MIN_VALUE;
    for (UsageCluster currentGroup : myClusters) {
      double similarity = findMinimalSimilarity(currentGroup, features, mySimilarityThreshold);
      if (maxSimilarity < similarity) {
        if (similarity == 1.0) {
          return currentGroup;
        }
        mostUsageCluster = currentGroup;
        maxSimilarity = similarity;
      }
    }
    return mostUsageCluster;
  }

  private static double findMinimalSimilarity(@NotNull UsageCluster usageCluster, Bag newUsageFeatures, double threshold) {
    final BagsDistanceCalculator bagsDistanceCalculator = new BagsDistanceCalculator(newUsageFeatures, threshold);
    double min = Double.MAX_VALUE;
    for (SimilarUsage usage : usageCluster.getUsages()) {
      final double similarity = bagsDistanceCalculator.similarity(usage.getFeatures());
      if (similarity < min) {
        min = similarity;
      }
      if (min == 0.0) {
        return min;
      }
    }
    return min;
  }

  public static @Nullable ClusteringSearchSession createClusteringSessionIfEnabled() {
    return isSimilarUsagesClusteringEnabled() ? new ClusteringSearchSession() : null;
  }

  public static boolean isSimilarUsagesClusteringEnabled() {
    return Registry.is("similarity.find.usages.enable") && ApplicationManager.getApplication().isInternal();
  }
}
