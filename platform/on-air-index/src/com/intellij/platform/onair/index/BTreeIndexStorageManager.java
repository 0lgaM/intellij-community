// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.index;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.platform.onair.storage.StorageImpl;
import com.intellij.platform.onair.storage.api.Address;
import com.intellij.platform.onair.storage.api.Novelty;
import com.intellij.platform.onair.storage.api.NoveltyImpl;
import com.intellij.platform.onair.storage.api.Storage;
import com.intellij.platform.onair.tree.BTree;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.IndexInfrastructure;
import com.intellij.util.indexing.IndexStorageManager;
import com.intellij.util.indexing.VfsAwareIndexStorage;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.PersistentMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BTreeIndexStorageManager implements IndexStorageManager {
  private static final int FORWARD_STORAGE_KEY_SIZE = 6;
  private static final int DUMMY = Integer.MAX_VALUE / 2;

  private static final Function<Integer, Integer> LOCAL_TO_REMOTE_DUMMY = local -> local + DUMMY;
  private static final Function<Integer, Integer> REMOTE_TO_LOCAL_DUMMY = remote -> remote - DUMMY;

  public final Storage storage;
  public final ConcurrentHashMap<String, BTreeIndexStorage> indexStorages = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<String, BTreeIntPersistentMap> forwardIndices = new ConcurrentHashMap<>();
  public final Novelty indexNovelty;
  public final Map indexHeads;
  public final BTree forwardStorage;

  public BTreeIndexStorageManager() {
    try {
      String revision = System.getProperty("onair.index.revision");
      String cacheHost = System.getProperty("onair.index.cache.host");
      String cachePort = System.getProperty("onair.index.cache.port", "11211");
      if (cacheHost != null) {
        storage = new StorageImpl(new InetSocketAddress(cacheHost, Integer.parseInt(cachePort)));
      }
      else {
        storage = new Storage() {
          @Override
          public @NotNull byte[] lookup(@NotNull Address address) {
            return new byte[0];
          }

          @Override
          public @NotNull Address alloc(@NotNull byte[] what) {
            return null;
          }

          @Override
          public void prefetch(@NotNull Address address, @NotNull byte[] bytes, @NotNull BTree tree, int size, byte type, int mask) {

          }

          @Override
          public void store(@NotNull Address address, @NotNull byte[] bytes) {

          }
        };
      }

      final NoveltyImpl novelty = new NoveltyImpl(FileUtil.createTempFile("novelty-", ".here"));
      if (revision != null && !revision.trim().isEmpty()) {
        indexHeads = downloadIndexData(revision);

        @SuppressWarnings("unchecked") List<String> addr = (List<String>)(indexHeads.get("forward-indices"));
        // one table to rule them all
        forwardStorage = BTree.load(storage, FORWARD_STORAGE_KEY_SIZE, Address.fromStrings(addr));
      }
      else {
        indexHeads = null;
        forwardStorage = BTree.create(novelty, storage, FORWARD_STORAGE_KEY_SIZE);
      }
      indexNovelty = novelty;
    }
    catch (IOException e) {
      throw new RuntimeException();
    }
  }

  public static Map downloadIndexData(String revision) {
    String bucket = "onair-index-data";
    String region = "eu-central-1";
    try {
      InputStream stream =
        new URL("https://s3." + region + ".amazonaws.com/" + bucket + "?prefix=" + revision + "/index_meta").openStream();
      Element element = JDOMUtil.load(stream);

      List<String> files = element.getChildren().stream()
                                  .filter(e -> e.getName().equals("Contents"))
                                  .flatMap(e -> e.getChildren().stream())
                                  .filter(o -> o.getName().equals("Key"))
                                  .map(e -> e.getText())
                                  .map(s -> s.split("/")[2])
                                  .collect(Collectors.toList());

      for (String file : files) {
        String s3url = "https://s3." + region + ".amazonaws.com/" + bucket + "/" + revision + "/index_meta/" + file;
        ReadableByteChannel source = Channels.newChannel(new URL(s3url).openStream());
        File base = IndexInfrastructure.getIndexMeta();
        base.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(base, file))) {
          fos.getChannel().transferFrom(source, 0, Long.MAX_VALUE);
        }
      }

      InputStream is = new URL(
        "https://s3." + region + ".amazonaws.com/" + bucket + "/" + revision + "/meta").openStream();

      String str = CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));

      return new GsonBuilder().create().fromJson(str, Map.class);
    }
    catch (Exception e) {
      throw new RuntimeException("exception downloading index data for revision " + revision, e);
    }
  }

  @Override
  public <V> PersistentMap<Integer, V> createForwardIndexStorage(ID<?, ?> indexId, DataExternalizer<V> valueExternalizer) {
    BTreeIntPersistentMap<V> map = new BTreeIntPersistentMap<>(indexId.getUniqueId(), valueExternalizer, indexNovelty, forwardStorage);
    forwardIndices.put(indexId.getName(), map);
    return new BTreeIndexStorageManagerDelegatingPersistentMap<>(this, indexId, LOCAL_TO_REMOTE_DUMMY, REMOTE_TO_LOCAL_DUMMY);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <K, V> VfsAwareIndexStorage<K, V> createIndexStorage(ID<?, ?> indexId,
                                                              KeyDescriptor<K> keyDescriptor,
                                                              DataExternalizer<V> valueExternalizer,
                                                              int cacheSize,
                                                              boolean keyIsUniqueForIndexedFile,
                                                              boolean buildKeyHashToVirtualFileMapping) {
    final BTreeIndexStorage.AddressDescriptor address;
    final int newRevision = 17;
    int baseRevision = -1;
    if (indexHeads != null) {
      Map m = (Map)(((Map)indexHeads.get("inverted-indices")).get(indexId.getName()));
      final List<String> invertedAddress = (List<String>)m.get("inverted");
      final List<String> internaryAddress = (List<String>)m.get("internary");
      // final List<String> hashToVirtualFile = (List<String>)m.get("hash-to-file");
      Address internary = internaryAddress != null ? Address.fromStrings(internaryAddress) : null;
      address = new BTreeIndexStorage.AddressDescriptor(
        internary,
        Address.fromStrings(invertedAddress)/*, Address.fromStrings(hashToVirtualFile)*/
      );
      baseRevision = Integer.parseInt((String)indexHeads.get("revision-int"));
    }
    else {
      address = null;
    }
    BTreeIndexStorage<K, V> indexStorage =
      new BTreeIndexStorage<>(keyDescriptor,
                              valueExternalizer,
                              storage,
                              indexNovelty,
                              address,
                              cacheSize,
                              newRevision,
                              baseRevision);
    indexStorages.put(indexId.getName(), indexStorage);
    return new BTreeIndexStorageManagerDelegatingIndexStorage<>(this, indexId, LOCAL_TO_REMOTE_DUMMY, REMOTE_TO_LOCAL_DUMMY);
  }
}
