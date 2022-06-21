// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet")

package org.jetbrains.intellij.build.tasks

import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.rt.execution.junit.FileComparisonFailure
import com.intellij.util.PathUtilRt
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.io.IOUtils
import org.jetbrains.intellij.build.io.readZipFile
import org.jetbrains.intellij.build.io.writeNewZip
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*
import java.util.function.BiConsumer
import java.util.zip.ZipEntry
import kotlin.io.path.inputStream
import kotlin.io.path.readText

// 0100000
private const val fileFlag = 32768
// 0755
const val executableFileUnixMode = fileFlag or 493

fun packInternalUtilities(outFile: Path, files: List<Path>) {
  writeNewZip(outFile, compress = true) { writer ->
    for (file in files) {
      writer.file(file.fileName.toString(), file)
    }

    readZipFile(files.last()) { name, entry ->
      if (name.endsWith(".xml")) {
        writer.uncompressedData(name, entry.getByteBuffer())
      }
    }
  }
}

private fun failIfContentNotEqualOrFalse(file1: Path, file2: Path, message: String): Boolean {
  if (IOUtils.contentEquals(file1.inputStream(), file2.inputStream()))
    return false

  val file1Text = file1.readText()
  val file2Text = file2.readText()
  if (!file1Text.take(1024).all { it == '\t' || it == '\n' || it == '\r' || it.code in 32..126 } ||
      !file2Text.take(1024).all { it == '\t' || it == '\n' || it == '\r' || it.code in 32..126 })
    error(message)
  throw FileComparisonFailure(message, file1Text, file2Text, file1.toString(), file2.toString())
}

fun filterFileIfAlreadyInZip(relativeFile: String, file: Path, zipFiles: MutableMap<String, Path>): Boolean {
  val found = zipFiles.put(relativeFile, file)
  if (found == null) {
    return true
  }

  return failIfContentNotEqualOrFalse(found, file, "Two files $found and $file with the target path $relativeFile have different content")
}

fun consumeDataByPrefix(file: Path, prefixWithEndingSlash: String, consumer: BiConsumer<String, ByteArray>) {
  readZipFile(file) { name, entry ->
    if (name.startsWith(prefixWithEndingSlash)) {
      consumer.accept(name, entry.getData())
    }
  }
}

fun ZipArchiveOutputStream.dir(startDir: Path,
                               prefix: String,
                               fileFilter: ((sourceFile: Path, relativePath: String) -> Boolean)? = null,
                               entryCustomizer: ((entry: ZipArchiveEntry, sourceFile: Path, relativePath: String) -> Unit)? = null) {
  val dirCandidates = ArrayDeque<Path>()
  dirCandidates.add(startDir)
  val tempList = ArrayList<Path>()
  while (true) {
    val dir = dirCandidates.pollFirst() ?: break
    tempList.clear()

    val dirStream = try {
      Files.newDirectoryStream(dir)
    }
    catch (e: NoSuchFileException) {
      continue
    }

    dirStream.use {
      tempList.addAll(it)
    }

    tempList.sort()
    for (file in tempList) {
      val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      if (attributes.isDirectory) {
        dirCandidates.add(file)
      }
      else if (attributes.isSymbolicLink) {
        val entry = ZipArchiveEntryAssertName(prefix + FileUtilRt.toSystemIndependentName(startDir.relativize(file).toString()))
        entry.method = ZipEntry.STORED
        entry.lastModifiedTime = zeroTime
        entry.unixMode = Files.readAttributes(file, "unix:mode", LinkOption.NOFOLLOW_LINKS).get("mode") as Int
        val data = (prefix + startDir.relativize(Files.readSymbolicLink(file))).toByteArray()
        entry.size = data.size.toLong()
        putArchiveEntry(entry)
        write(data)
        closeArchiveEntry()
      }
      else {
        assert(attributes.isRegularFile)

        val relativePath = FileUtilRt.toSystemIndependentName(startDir.relativize(file).toString())
        if (fileFilter != null && !fileFilter(file, relativePath)) {
          continue
        }

        val entry = ZipArchiveEntryAssertName(prefix + relativePath)
        entry.size = attributes.size()
        entryCustomizer?.invoke(entry, file, relativePath)
        writeFileEntry(file, entry, this)
      }
    }
  }
}

fun ZipArchiveOutputStream.entryToDir(file: Path, zipPath: String) {
  entry("$zipPath/${file.fileName}", file)
}

private val zeroTime = FileTime.fromMillis(0)

fun ZipArchiveOutputStream.entry(name: String, file: Path, unixMode: Int = -1) {
  val entry = ZipArchiveEntryAssertName(name)
  if (unixMode != -1) {
    entry.unixMode = unixMode
  }
  writeFileEntry(file, entry, this)
}

fun ZipArchiveOutputStream.entry(name: String, data: ByteArray) {
  val entry = ZipArchiveEntryAssertName(name)
  entry.size = data.size.toLong()
  entry.lastModifiedTime = zeroTime
  putArchiveEntry(entry)
  write(data)
  closeArchiveEntry()
}

fun assertRelativePathIsCorrectForPackaging(relativeName: String) {
  if (relativeName.isEmpty()) {
    throw IllegalArgumentException("relativeName must not be empty")
  }

  if (relativeName.startsWith("/")) {
    throw IllegalArgumentException("relativeName must not be an absolute path: $relativeName")
  }

  if (relativeName.contains('\\')) {
    throw IllegalArgumentException("relativeName must not contain backslash '\\': $relativeName")
  }

  for (component in relativeName.split('/')) {
    if (!component.all { it.code in 32..127 }) {
      throw IllegalArgumentException("relativeName must contain only ASCII (32 <= c <= 127) chars: $relativeName")
    }

    if (component.endsWith('.')) {
      throw IllegalArgumentException("path component '$component' must not end with dot (.), it fails under Windows: $relativeName")
    }

    if (component.endsWith(' ')) {
      throw IllegalArgumentException("path component '$component' must not end with space, it fails under Windows: $relativeName")
    }

    // WINDOWS is most restrictive
    if (!PathUtilRt.isValidFileName(component, PathUtilRt.Platform.WINDOWS, true, null)) {
      throw IllegalArgumentException("path component '$component' is not valid for Windows: $relativeName")
    }
  }
}

private fun writeFileEntry(file: Path, entry: ZipArchiveEntry, out: ZipArchiveOutputStream) {
  entry.lastModifiedTime = zeroTime
  out.putArchiveEntry(entry)
  Files.copy(file, out)
  out.closeArchiveEntry()
}
