// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util;

import org.jetbrains.annotations.NotNull;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@code Processor<Result> -> Processor<Base>}
 */
public interface Preprocessor<Result, Base> extends Function<Processor<? super Result>, Processor<? super Base>> {

  /**
   * @return {@link Base base} processor that processes and feeds elements into {@link Result result} processor
   */
  @NotNull
  @Override
  Processor<? super Base> apply(@NotNull Processor<? super Result> processor);

  /**
   * @return preprocessor returning original processor,
   * meaning elements from base processor will be passed into result processor as is.
   * Note that no casts are required because {@link Base} is a subtype of {@link Result}
   */
  @NotNull
  static <Result, Base extends Result> Preprocessor<Result, Base> id() {
    return IdPreprocessor.getInstance();
  }

  /**
   * {@code (Processor<R> -> Processor<I>) -> (Processor<I> -> Processor<B>) -> (Processor<R> -> Processor<B>)}
   *
   * @param <R> result type
   * @param <I> intermediate type
   * @param <B> base type
   */
  @NotNull
  static <R, I, B> Preprocessor<R, B> andThen(@NotNull Preprocessor<? extends R, ? super I> before,
                                              @NotNull Preprocessor<? extends I, ? super B> after) {
    Preprocessor<?, ?> id = id();
    if (before == id) {
      //noinspection unchecked
      return (Preprocessor<R, B>)after;
    }
    else if (after == id) {
      //noinspection unchecked
      return (Preprocessor<R, B>)before;
    }
    return v -> after.apply(before.apply(v));
  }

  @NotNull
  static <V> Preprocessor<V, V> filtering(@NotNull Predicate<? super V> predicate) {
    return processor -> v -> !predicate.test(v) || processor.process(v);
  }

  @NotNull
  static <Result, Base> Preprocessor<Result, Base> mapping(@NotNull Function<? super Base, ? extends Result> map) {
    return processor -> value -> {
      Result result = map.apply(value);
      return result == null || processor.process(result);
    };
  }
}
