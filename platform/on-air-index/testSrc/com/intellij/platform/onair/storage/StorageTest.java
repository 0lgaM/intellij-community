// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.storage;

import com.intellij.platform.onair.storage.api.Address;
import com.intellij.platform.onair.storage.api.Storage;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class StorageTest {

  public static String host = "localhost";
  public static int port = 11211;
  public static int insertionCount = 1024 * 128; // 2.5GB total
  public static int valueSize = 1024; // 20KB

  // setup:
  // memcached --memory-limit=4096 --max-item-size=5242880
  @Test
  public void simpleTest() throws IOException {
    final Storage storage = new StorageImpl(new InetSocketAddress(host, port));
    final byte[] bytes = "Hello".getBytes(Charset.forName("UTF-8"));
    final Address hash = storage.alloc(bytes);
    storage.store(hash, bytes);
    Assert.assertArrayEquals(bytes, storage.lookup(hash));
    Assert.assertArrayEquals(bytes, storage.lookup(hash));
  }

  @Test
  public void notFoundTest() throws IOException {
    final Storage storage = new StorageImpl(new InetSocketAddress(host, port));
    Assert.assertNull(storage.lookup(new Address(-42, -42)));
  }

  @Test
  public void performanceTest() throws IOException {
    final Storage storage = new StorageImpl(new InetSocketAddress(host, port));
    long start = System.currentTimeMillis();
    final int valueSize = 1024 * 20;
    final int insertionCount = (int)(5L * 1024 * 1024 * 1024 / 2 / valueSize); // 2.5GB total
    Address[] addresses = new Address[insertionCount];
    for (int i = 0; i < insertionCount; i++) {
      final byte[] bytes = new byte[valueSize];
      bytes[(i % bytes.length)] = 42;
      addresses[i] = storage.alloc(bytes);
      storage.store(addresses[i], bytes);
    }
    System.out.println(String.format("Write time: %d ms", System.currentTimeMillis() - start)); // 5916 ms
    start = System.currentTimeMillis();
    for (int i = 0; i < insertionCount; i++) {
      final byte[] bytes = new byte[valueSize];
      bytes[(i % bytes.length)] = 42;
      final Address address = addresses[i];
      byte[] lookup = storage.lookup(address);
      if (lookup == null) {
        storage.lookup(address);
      }
      Assert.assertArrayEquals(bytes, lookup);
    }
    System.out.println(String.format("Read time: %d ms", System.currentTimeMillis() - start)); // 10663 ms
  }

  @Test
  public void latencyTest() throws IOException {

    final Storage storage = new StorageImpl(new InetSocketAddress(host, port));
    int valueSize = 1024 * 20;

    final byte[] bytes = new byte[valueSize];

    long total = 0;
    for (int i = 0; i < 10000; i++) {
      new Random().nextBytes(bytes);
      long start = System.currentTimeMillis();
      final Address address = storage.alloc(bytes);
      storage.store(address, bytes);
      byte[] result = storage.lookup(address);
      Assert.assertArrayEquals(result, bytes);
      long end = System.currentTimeMillis();
      if (i > 9000) {
        total += end - start;
      }
    }
    System.out.println(String.format("Average write time: %f ms", total / 1000.0)); // 0.12ms
  }

  @Test
  public void cacheTest() throws IOException {
    final Storage storage = new StorageImpl(new InetSocketAddress(host, port));

    final List<byte[]> entries = new ArrayList<>();
    final List<Address> keys = new ArrayList<>();
    final int uniqueEntries = 10000;
    final int entrySize = 1024 * 24;

    for (int i = 0; i < uniqueEntries; i++) {
      final byte[] bytes = new byte[entrySize];
      new Random().nextBytes(bytes);
      entries.add(bytes);
      final Address result = storage.alloc(bytes);
      storage.store(result, bytes);
      keys.add(result);
    }

    final int pollsCount = 100000;
    int total = 0;

    for (int i = 0; i < pollsCount; i++) {

      int randomIndex = new Random().nextInt(uniqueEntries);
      Address randomAddress = keys.get(randomIndex);

      long start = System.currentTimeMillis();
      byte[] result = storage.lookup(randomAddress);
      long end = System.currentTimeMillis();

      total += end - start;

      Assert.assertArrayEquals(result, entries.get(randomIndex));
    }

    System.out.println(String.format("Average write time: %f ms", total / (float)pollsCount));
  }
}
