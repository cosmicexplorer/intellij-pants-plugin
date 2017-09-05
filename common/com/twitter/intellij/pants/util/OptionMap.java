// Copyright 2017 Pants project contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).

package com.twitter.intellij.pants.util;

import java.util.Map;
import java.util.Optional;

public interface OptionMap<K, V> extends Map<K, V> {
  default Optional<V> getOpt(K key) {
    return Optional.ofNullable(this.get(key));
  }
}
