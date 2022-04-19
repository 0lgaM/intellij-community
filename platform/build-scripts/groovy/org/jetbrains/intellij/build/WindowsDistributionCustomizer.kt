// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build

import org.jetbrains.intellij.build.impl.support.RepairUtilityBuilder
import java.nio.file.Paths

abstract class WindowsDistributionCustomizer {
  /**
   * Path to 256x256 *.ico file for Windows distribution
   */
  var icoPath: String? = null

  /**
   * Path to ico file for EAP builds (if {@code null} {@link #icoPath} will be used)
   */
  var icoPathForEAP: String? = null

  /**
   * If {@code true} *.bat files (productName.bat and inspect.bat) will be included into the distribution
   */
  var includeBatchLaunchers = true

  /**
   * If {@code true} a Zip archive containing the installation will be produced
   */
  var buildZipArchive = true

  /**
   * If {@code true} JetBrains RE jre will be added to a zip archive
   */
  var zipArchiveWithBundledJre = true

  /**
   * If {@code true} Windows Installer will associate *.ipr files with the IDE in Registry
   */
  var associateIpr = true

  /**
   * Path to a directory containing images for installer: logo.bmp, headerlogo.bmp, install.ico, uninstall.ico
   */
  var installerImagesPath: String? = null

  /**
   * List of file extensions (without leading dot) which installer will suggest to associate with the product
   */
  var fileAssociations: MutableList<String> = mutableListOf()

  /**
   * Paths to files which will be used to overwrite the standard *.nsi files
   */
  var customNsiConfigurationFiles: MutableList<String> = mutableListOf()

  /**
   * Path to a file which contains set of properties to manage UI options when installing the product in silent mode. If {@code null}
   * the default platform/build-scripts/resources/win/nsis/silent.config will be used.
   */
  var silentInstallationConfig: String? = null

  /**
   * Name of the root directory in Windows .zip archive
   */
  // method is used by AndroidStudioProperties.groovy (https://bit.ly/3heXKlQ)
  open fun getRootDirectoryName(applicationInfo: ApplicationInfoProperties, buildNumber: String): String {
    return ""
  }

  /**
   * Name of the root product windows installation directory and Desktop ShortCut
   */
  open fun getNameForInstallDirAndDesktopShortcut(applicationInfo: ApplicationInfoProperties, buildNumber: String): String =
    "${getFullNameIncludingEdition(applicationInfo)} ${if (applicationInfo.isEAP) buildNumber else applicationInfo.fullVersion}"

  /**
   * Override this method to copy additional files to Windows distribution of the product.
   * @param targetDirectory contents of this directory will be packed into zip archive and exe installer, so when the product is installed
   * it'll be placed under its root directory.
   */
  open fun copyAdditionalFiles(context: BuildContext, targetDirectory: String) {
    RepairUtilityBuilder.bundle(context, OsFamily.WINDOWS, JvmArchitecture.x64, Paths.get(targetDirectory))
  }

  /**
   * The returned name will be shown in Windows Installer and used in Registry keys
   */
  open fun getFullNameIncludingEdition(applicationInfo: ApplicationInfoProperties): String = applicationInfo.productName

  /**
   * The returned name will be used to create links on Desktop
   */
  open fun getFullNameIncludingEditionAndVendor(applicationInfo: ApplicationInfoProperties): String =
    applicationInfo.shortCompanyName + " " + getFullNameIncludingEdition(applicationInfo)

  open fun getUninstallFeedbackPageUrl(applicationInfo: ApplicationInfoProperties): String? {
    return null
  }

  /**
   * Relative paths to files not in `bin` directory of Windows distribution which should be signed
   */
  open fun getBinariesToSign(context: BuildContext): List<String>  {
    return listOf()
  }
}
