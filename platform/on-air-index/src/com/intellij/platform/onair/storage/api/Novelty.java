// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform.onair.storage.api;

public interface Novelty {

  // result must be less than 0
  long alloc(byte[] bytes);

  void free(long address);

  byte[] lookup(long address);

  void update(long address, byte[] bytes);

  Novelty unsynchronizedCopy();
}
