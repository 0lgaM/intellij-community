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
package com.intellij.util.lang;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.reference.SoftReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSigner;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.intellij.openapi.util.Pair.pair;

class JarLoader extends Loader {
  private static final List<Pair<Resource.Attribute, Attributes.Name>> PACKAGE_FIELDS = Arrays.asList(
    pair(Resource.Attribute.SPEC_TITLE, Attributes.Name.SPECIFICATION_TITLE),
    pair(Resource.Attribute.SPEC_VERSION, Attributes.Name.SPECIFICATION_VERSION),
    pair(Resource.Attribute.SPEC_VENDOR, Attributes.Name.SPECIFICATION_VENDOR),
    pair(Resource.Attribute.IMPL_TITLE, Attributes.Name.IMPLEMENTATION_TITLE),
    pair(Resource.Attribute.IMPL_VERSION, Attributes.Name.IMPLEMENTATION_VERSION),
    pair(Resource.Attribute.IMPL_VENDOR, Attributes.Name.IMPLEMENTATION_VENDOR));

  private final File myCanonicalFile;
  private final boolean myCanLockJar; // true implies that the .jar file will not be modified in the lifetime of the JarLoader
  private SoftReference<JarMemoryLoader> myMemoryLoader;
  private volatile SoftReference<JarFile> myJarFileSoftReference; // Used only when myCanLockJar==true
  private final Map<Resource.Attribute, String> myAttributes;
  private final URL myJarFileUrl;

  JarLoader(URL url, @SuppressWarnings("unused") boolean canLockJar, int index, boolean preloadJarContents) throws IOException {
    super(new URL("jar", "", -1, url + "!/"), index);

    myJarFileUrl = url;
    myCanonicalFile = new File(FileUtil.unquote(url.getFile())).getCanonicalFile();
    myCanLockJar = canLockJar;

    ZipFile zipFile = getJarFile(); // IOException from opening is propagated to caller if zip file isn't valid,
    try {
      myAttributes = getAttributes(zipFile);
      if (preloadJarContents) {
        JarMemoryLoader loader = JarMemoryLoader.load(zipFile, url, getBaseURL(), myAttributes);
        if (loader != null) {
          myMemoryLoader = new SoftReference<JarMemoryLoader>(loader);
        }
      }
    }
    finally {
      releaseZipFile(zipFile);
    }
  }

  @Nullable
  private static Map<Resource.Attribute, String> getAttributes(ZipFile zipFile) {
    ZipEntry entry = zipFile.getEntry(JarFile.MANIFEST_NAME);
    if (entry == null) return null;

    Map<Resource.Attribute, String> map = null;
    try {
      InputStream stream = zipFile.getInputStream(entry);
      try {
        Attributes attributes = new Manifest(stream).getMainAttributes();
        for (Pair<Resource.Attribute, Attributes.Name> p : PACKAGE_FIELDS) {
          String value = attributes.getValue(p.second);
          if (value != null) {
            if (map == null) map = new EnumMap<Resource.Attribute, String>(Resource.Attribute.class);
            map.put(p.first, value);
          }
        }
      }
      finally {
        stream.close();
      }
    }
    catch (Exception ignored) { }
    return map;
  }

  @NotNull
  @Override
  public ClasspathCache.LoaderData buildData() throws IOException {
    ZipFile zipFile = getJarFile();
    try {
      ClasspathCache.LoaderData loaderData = new ClasspathCache.LoaderData();
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        loaderData.addResourceEntry(name);
        loaderData.addNameEntry(name);
      }
      return loaderData;
    }
    finally {
      releaseZipFile(zipFile);
    }
  }

  @Override
  @Nullable
  Resource getResource(String name, boolean flag) {
    JarMemoryLoader loader = myMemoryLoader != null? myMemoryLoader.get() : null;
    if (loader != null) {
      Resource resource = loader.getResource(name);
      if (resource != null) return resource;
    }

    try {
      JarFile jarFile = getJarFile();
      try {
        JarEntry entry = jarFile.getJarEntry(name);
        if (entry != null) {
          return new MyResource(entry.getCodeSigners(), myJarFileUrl, getBaseURL(), entry);
        }
      }
      finally {
        releaseZipFile(jarFile);
      }
    }
    catch (Exception e) {
      error("file: " + myCanonicalFile, e);
    }

    return null;
  }
  
  private class MyResource extends Resource {
    private final URL myUrl;
    private final CodeSigner[] mySigners;
    private final URL myCodeSourceUrl;
    private final ZipEntry myEntry;

    public MyResource(CodeSigner[] signers, URL codeSourceUrl, URL url, ZipEntry entry) throws IOException {
      mySigners = signers;
      myCodeSourceUrl = codeSourceUrl;
      myUrl = new URL(url, entry.getName());
      myEntry = entry;
    }

    @Override
    public URL getCodeSourceUrl() {
      return myCodeSourceUrl;
    }

    @Override
    public CodeSigner[] getCodeSigners() {
      return mySigners;
    }

    @Override
    public URL getURL() {
      return myUrl;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(getBytes());
    }

    @Override
    public byte[] getBytes() throws IOException {
      ZipFile file = getJarFile();
      try {
        return FileUtil.loadBytes(file.getInputStream(myEntry), (int)myEntry.getSize());
      } finally {
        releaseZipFile(file);
      }
    }

    @Override
    public String getValue(Attribute key) {
      return myAttributes != null ? myAttributes.get(key) : null;
    }
  }

  protected void error(String message, Throwable t) {
    Logger.getInstance(JarLoader.class).error(message, t);
  }

  private static final Object ourLock = new Object();

  @NotNull
  private JarFile getJarFile() throws IOException {
    // This code is executed at least 100K times (O(number of classes needed to load)) and it takes considerable time to open ZipFile's
    // such number of times so we store reference to ZipFile if we allowed to lock the file (assume it isn't changed)
    if (myCanLockJar) {
      JarFile jarFile = SoftReference.dereference(myJarFileSoftReference);
      if (jarFile != null) return jarFile;

      synchronized (ourLock) {
        jarFile = SoftReference.dereference(myJarFileSoftReference);
        if (jarFile != null) return jarFile;

        // ZipFile's native implementation (ZipFile.c, zip_util.c) has path -> file descriptor cache
        jarFile = new JarFile(myCanonicalFile);
        myJarFileSoftReference = new SoftReference<JarFile>(jarFile);
        return jarFile;
      }
    }
    else {
      return new JarFile(myCanonicalFile);
    }
  }

  private void releaseZipFile(ZipFile zipFile) throws IOException {
    // Closing of zip file when myCanLockJar=true happens in ZipFile.finalize
    if (!myCanLockJar) {
      zipFile.close();
    }
  }

  @Override
  public String toString() {
    return "JarLoader [" + myCanonicalFile + "]";
  }
}
