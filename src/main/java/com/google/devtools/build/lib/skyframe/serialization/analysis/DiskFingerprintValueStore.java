package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class DiskFingerprintValueStore implements FingerprintValueStore {

  private final LocalSkycacheStorage storage;

  public DiskFingerprintValueStore(LocalSkycacheStorage storage) {
    this.storage = storage;
  }

  private final java.util.concurrent.atomic.AtomicLong bytesReceived = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong entriesWritten = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong entriesFound = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong entriesNotFound = new java.util.concurrent.atomic.AtomicLong();


  @Override
  public Stats getStats() {
    return new Stats(
        bytesReceived.get(),
        0, // valueBytesSent
        0, // keyBytesSent
        entriesWritten.get(),
        entriesFound.get(),
        entriesNotFound.get(),
        0, // getBatches
        0, // setBatches
        com.google.common.collect.ImmutableList.of(), // getLatencyMicros
        com.google.common.collect.ImmutableList.of(), // setLatencyMicros
        com.google.common.collect.ImmutableList.of(), // getBatchLatencyMicros
        com.google.common.collect.ImmutableList.of()  // setBatchLatencyMicros
    );
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    entriesWritten.incrementAndGet();
    String key = computeKey(fingerprint);
    InputStream in = new ByteArrayInputStream(serializedBytes);

    try {
      storage.save(key, in);
      return WriteStatuses.immediateWriteStatus(true); // Assuming always novel for this simple store
    } catch (IOException e) {
      return WriteStatuses.immediateFailedWriteStatus(e);
    }
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint)
      throws IOException {
    String key = computeKey(fingerprint);
    if (!storage.exists(key)) {
      entriesNotFound.incrementAndGet();
      return com.google.common.util.concurrent.Futures.immediateFuture(null);
    }
    entriesFound.incrementAndGet();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    storage.load(key, out);
    byte[] bytes = out.toByteArray();
    bytesReceived.addAndGet(bytes.length);
    return immediateFuture(bytes);
  }

  private static String computeKey(KeyBytesProvider fingerprint) {
    if (fingerprint instanceof com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint) {
      return base16().lowerCase().encode(fingerprint.toBytes());
    } else {
      // Fallback for StringKey (or other implementations if added).
      // Use a hash to keep the filename short and safe.
      return base16().lowerCase().encode(
          com.google.common.hash.Hashing.murmur3_128().hashBytes(fingerprint.toBytes()).asBytes());
    }
  }
}
