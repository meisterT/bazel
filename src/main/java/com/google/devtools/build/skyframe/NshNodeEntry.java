// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;

/**
 * Interface for Skyframe nodes that store a 128-bit Node State Hash (NSH).
 */
public interface NshNodeEntry {
  long getNshLow();
  long getNshHigh();
  void setNsh(long low, long high);

  static void computeAndSetNsh(
      NodeEntry entry,
      SkyKey key,
      @Nullable SkyValue value,
      @Nullable java.util.function.Function<SkyKey, byte[]> keySerializer,
      @Nullable Iterable<? extends NshNodeEntry> depNshs) {
    if (!(entry instanceof NshNodeEntry nshEntry)) {
      return;
    }
    String functionName = key.functionName().getName();
    if (key.toString().contains("pkg:hello") || key.toString().contains("pkg/hello.txt")) {
      System.err.println("DEBUG Skycache: NshNodeEntry.computeAndSetNsh called for " + key + " (function: " + functionName + ") with keySerializer=" + keySerializer + ", value=" + (value != null ? value.getClass().getName() : "null"));
    }
    long low = 0;
    long high = 0;
    if (functionName.equals("FILE_STATE") && value != null) {
      try {
        java.lang.reflect.Method method = getAccessibleMethod(value.getClass(), "getValueFingerprint");
        byte[] fp = (byte[]) method.invoke(value);
        if (fp != null) {
          HashCode hc = Hashing.murmur3_128().hashBytes(fp);
          ByteBuffer bb = ByteBuffer.wrap(hc.asBytes()).order(ByteOrder.LITTLE_ENDIAN);
          low = bb.getLong();
          high = bb.getLong();
          if (key.toString().contains("pkg:hello") || key.toString().contains("pkg/hello.txt")) {
            System.err.println("DEBUG Skycache: FILE_STATE NSH computed for " + key + ": " + String.format("%016x%016x", high, low));
          }
        }
      } catch (Exception e) {
        System.err.println("DEBUG Skycache: NshNodeEntry: FILE_STATE NSH computation failed for " + key + ": " + e);
      }
    } else if (functionName.equals("DIRECTORY_LISTING_STATE") && value != null) {
      try {
        java.lang.reflect.Method method = getAccessibleMethod(value.getClass(), "getDirents");
        Object dirents = method.invoke(value); 
        HashCode hc = Hashing.murmur3_128().hashString(dirents.toString(), StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.wrap(hc.asBytes()).order(ByteOrder.LITTLE_ENDIAN);
        low = bb.getLong();
        high = bb.getLong();
      } catch (Exception e) {
        System.err.println("DEBUG Skycache: NshNodeEntry: DIRECTORY_LISTING_STATE NSH computation failed for " + key + ": " + e);
      }
    } else if (keySerializer != null) {
      byte[] keyBytes = keySerializer.apply(key);
      if (key.toString().contains("pkg:hello") || key.toString().contains("pkg/hello.txt")) {
        System.err.println("DEBUG Skycache: keyBytes for " + key + " is " + (keyBytes == null ? "null" : "not null (length " + keyBytes.length + ")"));
      }
      if (keyBytes != null) {
        Hasher hasher = Hashing.murmur3_128().newHasher();
        hasher.putBytes(keyBytes);
        int depCount = 0;
        if (depNshs != null) {
          for (NshNodeEntry depEntry : depNshs) {
            hasher.putLong(depEntry.getNshLow());
            hasher.putLong(depEntry.getNshHigh());
            depCount++;
          }
        }
        HashCode hc = hasher.hash();
        ByteBuffer bb = ByteBuffer.wrap(hc.asBytes()).order(ByteOrder.LITTLE_ENDIAN);
        low = bb.getLong();
        high = bb.getLong();
        if (key.toString().contains("pkg:hello") || key.toString().contains("pkg/hello.txt")) {
          System.err.println("DEBUG Skycache: Generic NSH computed for " + key + ": " + String.format("%016x%016x", high, low) + " with " + depCount + " deps");
        }
      }
    }
    nshEntry.setNsh(low, high);
  }

  private static java.lang.reflect.Method getAccessibleMethod(Class<?> clazz, String name) throws NoSuchMethodException {
    if (java.lang.reflect.Modifier.isPublic(clazz.getModifiers())) {
      try {
        return clazz.getMethod(name);
      } catch (NoSuchMethodException e) {
        // ignore, might be in superclass
      }
    }
    for (Class<?> iface : clazz.getInterfaces()) {
      try {
        return getAccessibleMethod(iface, name);
      } catch (NoSuchMethodException e) {
        // ignore
      }
    }
    Class<?> superclass = clazz.getSuperclass();
    if (superclass != null) {
      return getAccessibleMethod(superclass, name);
    }
    throw new NoSuchMethodException(name);
  }
}
