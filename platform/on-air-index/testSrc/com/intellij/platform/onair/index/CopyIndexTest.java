// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.index;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.intellij.platform.onair.storage.api.Novelty;
import com.intellij.psi.stubs.StubIdList;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.PersistentHashMap;
import com.intellij.platform.onair.storage.StorageImpl;
import com.intellij.platform.onair.storage.api.NoveltyImpl;
import com.intellij.platform.onair.tree.BTree;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.intellij.util.io.PagedFileStorage.MB;

public class CopyIndexTest {
  private static final HashFunction HASH = Hashing.goodFastHash(128);

  public static final int ITERATIONS = 5;
  public static final String FOLDER =
    System.getProperty("com.intellij.platform.onair.indexing.CopyIndexTest.dir", "/Users/pavel/work/index-sandbox");
  public static final String host = "localhost";
  public static final int port = 11211;
  public static final String PHM = FOLDER + "/idea/java.class.shortname.storage";
  public static final String PHM_I = FOLDER + "/trash/java.class.shortname.storage.smth.";

  @Test
  public void testAll() throws IOException {
    final StorageImpl storage = new StorageImpl(new InetSocketAddress(host, port)); // DummyStorageImpl.INSTANCE;
    final Map<String, StubIdList> content = new HashMap<>();
    final Map<byte[], byte[]> rawContent = new HashMap<>();
    final PersistentHashMap<String, StubIdList> phm = makePHM(PHM);
    try {
      final AtomicLong size = new AtomicLong();
      phm.processKeys(key -> {
        try {
          size.addAndGet(key.length());
          StubIdList list = phm.get(key);
          int listSize = list.size();
          size.addAndGet(listSize * 8);
          byte[] valueBytes = key.getBytes(Charset.forName("UTF-8"));
          HashCode hashCode = HASH.hashBytes(valueBytes);
          content.put(key, list);
          rawContent.put(hashCode.asBytes(), valueBytes);
          return true;
        }
        catch (IOException e) {
          return false;
        }
      });
      System.out.println("Data set size: " + size.get() / MB + "MB");
    }
    finally {
      phm.close();
    }
    boolean buildNovelty = true;
    if (buildNovelty) {
      final List<BTree> trees = new ArrayList<>();
      final List<BTree> remoteTrees = new ArrayList<>();
      System.out.println("Building novelty...");
      long start = System.currentTimeMillis();
      final NoveltyImpl novelty = new NoveltyImpl(new File(FOLDER + "/novelty/novelty.dat"));
      try {
        final Novelty.Accessor accessor = novelty.access();
        for (int i = 0; i < ITERATIONS; i++) {
          final BTree tree = BTree.create(accessor, storage, 16);
          rawContent.forEach((key, value) -> Assert.assertTrue(tree.put(accessor, key, value)));
          trees.add(tree);
        }
        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");
        int total = novelty.getSize();
        System.out.println("Written total: " + total / MB + "MB");
        System.out.println("Written per tree: " + total / ITERATIONS / MB + "MB");

        System.out.println("Check novelty reads...");
        start = System.currentTimeMillis();
        trees.forEach(tree -> rawContent.forEach((key, value) -> Assert.assertArrayEquals(value, tree.get(accessor, key))));
        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");

        System.out.println("Check write...");
        start = System.currentTimeMillis();
        trees.forEach(tree -> remoteTrees.add(BTree.load(storage, tree.getKeySize(), storage.bulkStore(tree, novelty))));
        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");
        System.out.println("Check storage reads...");
        start = System.currentTimeMillis();
        AtomicInteger step = new AtomicInteger();
        remoteTrees.forEach(tree -> rawContent.forEach((key, value) -> {
          step.incrementAndGet();
          Assert.assertArrayEquals(value, tree.get(Novelty.VOID_TXN, key));
        }));
        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");
      }
      finally {
        novelty.close();
        storage.close();
      }
    }
    boolean buildPHM = false;
    if (buildPHM) {
      final List<PersistentHashMap<String, StubIdList>> pHMs = new ArrayList<>();
      try {
        System.out.println("Building PHMs...");
        long start = System.currentTimeMillis();
        for (int i = 0; i < ITERATIONS; i++) {
          final PersistentHashMap<String, StubIdList> phmCopy = makePHM(PHM_I + i);
          pHMs.add(phmCopy);
          content.forEach((key, value) -> {
            try {
              phmCopy.put(key, value);
            }
            catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
        }

        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");

        System.out.println("Check PHM reads...");
        start = System.currentTimeMillis();
        pHMs.forEach(pHM -> content.forEach((key, value) -> {
          try {
            Assert.assertEquals(value, pHM.get(key));
          }
          catch (IOException e) {
            throw new RuntimeException(e);
          }
        }));
        System.out.println("Time: " + (System.currentTimeMillis() - start) / 1000 + "s");
      }
      finally {
        pHMs.forEach(phmCopy -> {
          try {
            phmCopy.close();
          }
          catch (IOException e) {
            e.printStackTrace(); // don't fail
          }
        });
      }
    }
  }

  @NotNull
  private static PersistentHashMap<String, StubIdList> makePHM(String name) throws IOException {
    return new PersistentHashMap<>(new File(name), EnumeratorStringDescriptor.INSTANCE, EXT);
  }

  private static DataExternalizer<StubIdList> EXT = new DataExternalizer<StubIdList>() {
    @Override
    public void save(@NotNull final DataOutput out, @NotNull final StubIdList value) throws IOException {
      int size = value.size();
      if (size == 0) {
        DataInputOutputUtil.writeINT(out, Integer.MAX_VALUE);
      }
      else if (size == 1) {
        DataInputOutputUtil.writeINT(out, value.get(0)); // most often case
      }
      else {
        DataInputOutputUtil.writeINT(out, -size);
        for (int i = 0; i < size; ++i) {
          DataInputOutputUtil.writeINT(out, value.get(i));
        }
      }
    }

    @NotNull
    @Override
    public StubIdList read(@NotNull final DataInput in) throws IOException {
      int size = DataInputOutputUtil.readINT(in);
      if (size == Integer.MAX_VALUE) {
        return new StubIdList();
      }
      else if (size >= 0) {
        return new StubIdList(size);
      }
      else {
        size = -size;
        int[] result = new int[size];
        for (int i = 0; i < size; ++i) {
          result[i] = DataInputOutputUtil.readINT(in);
        }
        return new StubIdList(result, size);
      }
    }
  };
}
