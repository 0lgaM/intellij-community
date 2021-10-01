package com.jetbrains.packagesearch.intellij.plugin.data

import com.intellij.openapi.module.Module
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.PackageModel
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.PackageVersion
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.PackagesToUpgrade
import com.jetbrains.packagesearch.intellij.plugin.ui.toolwindow.models.upgradeCandidateVersionOrNull
import com.jetbrains.packagesearch.packageversionutils.PackageVersionUtils

internal fun computePackageUpgrades(
    installedPackages: List<PackageModel.Installed>,
    onlyStable: Boolean
): PackagesToUpgrade {
    val updatesByModule = mutableMapOf<Module, MutableSet<PackagesToUpgrade.PackageUpgradeInfo>>()
    for (installedPackageModel in installedPackages) {
        val availableVersions = installedPackageModel.getAvailableVersions(onlyStable)
        if (installedPackageModel.remoteInfo == null || availableVersions.isEmpty()) continue

        for (usageInfo in installedPackageModel.usageInfo) {
            val currentVersion = usageInfo.version
            if (currentVersion is PackageVersion.Missing) continue

            val upgradeVersion = PackageVersionUtils.upgradeCandidateVersionOrNull(currentVersion, availableVersions)
            if (upgradeVersion != null) {
                updatesByModule.getOrCreate(usageInfo.projectModule.nativeModule) { mutableSetOf() } +=
                    PackagesToUpgrade.PackageUpgradeInfo(
                        installedPackageModel,
                        usageInfo,
                        upgradeVersion
                    )
            }
        }
    }

    return PackagesToUpgrade(updatesByModule)
}

private inline fun <K : Any, V : Any> MutableMap<K, V>.getOrCreate(key: K, crossinline creator: (K) -> V): V =
    this[key] ?: creator(key).let {
        this[key] = it
        return it
    }
