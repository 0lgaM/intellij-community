// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.storage;

import com.intellij.platform.onair.storage.api.Address;
import com.intellij.platform.onair.storage.api.Storage;
import com.intellij.platform.onair.tree.BTree;
import org.jetbrains.annotations.NotNull;

public class DummyStorageImpl implements Storage {
  public static final Storage INSTANCE = new DummyStorageImpl();

  private DummyStorageImpl() {
  }

  @NotNull
  @Override
  public byte[] lookup(@NotNull Address address) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Address alloc(@NotNull byte[] what) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void store(@NotNull Address address, @NotNull byte[] bytes) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void prefetch(@NotNull Address address, @NotNull byte[] bytes, @NotNull BTree tree, int size, byte type) {
  }
}
