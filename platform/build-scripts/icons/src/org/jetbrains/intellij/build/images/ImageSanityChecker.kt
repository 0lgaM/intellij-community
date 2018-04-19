/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.intellij.build.images

import org.jetbrains.intellij.build.images.ImageExtension.*
import org.jetbrains.intellij.build.images.ImageSanityCheckerBase.Severity.*
import org.jetbrains.intellij.build.images.ImageType.*
import org.jetbrains.jps.model.module.JpsModule
import java.awt.Dimension
import java.io.File
import java.util.*

abstract class ImageSanityCheckerBase(val projectHome: File, val ignoreSkipTag: Boolean) {
  fun check(module: JpsModule) {
    val allImages = ImageCollector(projectHome, false, ignoreSkipTag).collect(module)

    val (images, broken) = allImages.partition { it.file != null }
    log(Severity.ERROR, "image without base version", module, broken)

    checkHaveRetinaVersion(images, module)
    checkHaveCompleteIconSet(images, module)
    checkHaveValidSize(images, module)
    checkAreNotAmbiguous(images, module)
    checkSvgIconsFallbackVersions(images, module)
  }

  private fun checkHaveRetinaVersion(images: List<ImagePaths>, module: JpsModule) {
    process(images, Severity.INFO, "image without retina version", module) { image ->
      return@process image.getFiles(RETINA, RETINA_DARCULA).isNotEmpty()
    }
  }

  private fun checkHaveCompleteIconSet(images: List<ImagePaths>, module: JpsModule) {
    process(images, WARNING, "image without complete set of additional icons", module) { image ->
      val hasRetina = image.getFiles(RETINA).isNotEmpty()
      val hasDarcula = image.getFiles(DARCULA).isNotEmpty()
      val hasRetinaDarcula = image.getFiles(RETINA_DARCULA).isNotEmpty()

      if (hasRetinaDarcula) {
        return@process hasRetina && hasDarcula
      }
      else {
        return@process !hasRetina || !hasDarcula
      }
    }
  }

  private fun checkHaveValidSize(images: List<ImagePaths>, module: JpsModule) {
    process(images, WARNING, "icon with suspicious size", module) { image ->
      if (!isIcon(image.file!!)) return@process true
      val basicSizes = image.getFiles(BASIC, DARCULA).mapNotNull { imageSize(it) }.toSet()
      val retinaSizes = image.getFiles(RETINA, RETINA_DARCULA).mapNotNull { imageSize(it) }.toSet()

      if (basicSizes.size > 1) return@process false
      if (retinaSizes.size > 1) return@process false
      if (basicSizes.size == 1 && retinaSizes.size == 1) {
        val sizeBasic = basicSizes.single()
        val sizeRetina = retinaSizes.single()
        val sizeBasicTwice = Dimension(sizeBasic.width * 2, sizeBasic.height * 2)
        return@process sizeBasicTwice == sizeRetina
      }
      return@process true
    }
  }

  private fun checkAreNotAmbiguous(images: List<ImagePaths>, module: JpsModule) {
    process(images, WARNING, "image with ambiguous definition (has both '.png' and '.gif' versions)", module) { image ->
      val extensions = image.files.map { ImageExtension.fromFile(it) }
      return@process GIF !in extensions || PNG !in extensions
    }
  }

  private fun checkSvgIconsFallbackVersions(images: List<ImagePaths>, module: JpsModule) {
    process(images, WARNING, "svg image with non-fallback legacy version", module) { image ->
      val extensions = image.files.map { ImageExtension.fromFile(it) }
      if (SVG !in extensions) return@process true
      return@process image.files.filter { ImageExtension.fromFile(it) != SVG }.filter { ImageType.fromFile(it) != BASIC }.isEmpty()
    }
  }

  private fun process(images: List<ImagePaths>, severity: Severity, message: String, module: JpsModule,
                      processor: (ImagePaths) -> Boolean) {
    val result = ArrayList<ImagePaths>()
    images.forEach {
      if (!processor(it)) result.add(it)
    }
    log(severity, message, module, result)
  }

  internal abstract fun log(severity: Severity, message: String, module: JpsModule, images: Collection<ImagePaths>)

  enum class Severity { INFO, WARNING, ERROR }
}

class ImageSanityChecker(projectHome: File) : ImageSanityCheckerBase(projectHome, false) {
  private val infos: StringBuilder = StringBuilder()
  private val warnings: StringBuilder = StringBuilder()

  fun printInfo() {
    if (infos.isNotEmpty()) {
      println()
      println(infos)
    }
  }

  fun printWarnings() {
    if (warnings.isNotEmpty()) {
      println()
      println(warnings)
    }
    else {
      println()
      println("No warnings found")
    }
  }

  override fun log(severity: Severity, message: String, module: JpsModule, images: Collection<ImagePaths>) {
    val logger = when (severity) {
      ERROR -> warnings
      WARNING -> warnings
      INFO -> infos
    }
    val prefix = when (severity) {
      ERROR -> "ERROR:"
      WARNING -> "WARNING:"
      INFO -> "INFO:"
    }

    if (images.isEmpty()) return
    logger.append("$prefix $message found in module '${module.name}'\n")
    images.sortedBy { it.id }.forEach {
      logger.append("    ${it.id} - ${it.presentablePath.path}\n")
    }
    logger.append("\n")
  }
}
