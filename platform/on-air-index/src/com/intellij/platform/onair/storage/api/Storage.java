// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.storage.api;

import com.intellij.platform.onair.tree.BTree;
import org.jetbrains.annotations.NotNull;

public interface Storage {

  Storage VOID = new Storage() {
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
    public Address bulkStore(@NotNull Tree tree, @NotNull Novelty.Accessor novelty) {
      throw new UnsupportedOperationException();
    }
  };

  Address bulkStore(@NotNull Tree tree, @NotNull Novelty.Accessor novelty);

  @NotNull
  byte[] lookup(@NotNull Address address);

  @NotNull
  Address alloc(@NotNull byte[] what);

  void prefetch(@NotNull Address address, @NotNull byte[] bytes, @NotNull BTree tree, int size, byte type, int mask);
}
