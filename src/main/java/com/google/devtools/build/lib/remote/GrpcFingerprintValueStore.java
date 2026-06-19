// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.Platform;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.common.ActionKey;
import com.google.devtools.build.lib.remote.common.CacheNotFoundException;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * A {@link FingerprintValueStore} implementation that uses standard REv2 remote cache (CAS/AC)
 * protocol.
 */
public final class GrpcFingerprintValueStore implements FingerprintValueStore {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public static final Platform SKYCACHE_PLATFORM =
      Platform.newBuilder()
          .addProperties(
              Platform.Property.newBuilder().setName("type").setValue("skycache").build())
          .build();

  public static final Command DUMMY_COMMAND =
      Command.newBuilder()
          .addArguments("skycache-dummy")
          .setPlatform(SKYCACHE_PLATFORM)
          .build();

  private final RemoteCacheClient remoteCacheClient;
  private final DigestUtil digestUtil;
  private final RemoteActionExecutionContext context;

  public GrpcFingerprintValueStore(
      RemoteCacheClient remoteCacheClient, DigestUtil digestUtil) {
    this.remoteCacheClient = remoteCacheClient;
    this.digestUtil = digestUtil;
    // Use default context with ANY_CACHE policy
    this.context =
        RemoteActionExecutionContext.create(
            build.bazel.remote.execution.v2.RequestMetadata.getDefaultInstance());
  }

  public static ActionKey fingerprintToActionKey(
      KeyBytesProvider fingerprint, DigestUtil digestUtil, Platform platform) {
    String hexHash = HashCode.fromBytes(fingerprint.toBytes()).toString();
    Digest inputRootDigest = DigestUtil.buildDigest(hexHash, 0);

    Digest commandDigest = digestUtil.compute(DUMMY_COMMAND);

    Action action =
        Action.newBuilder()
            .setInputRootDigest(inputRootDigest)
            .setCommandDigest(commandDigest)
            .setPlatform(platform)
            .build();

    return digestUtil.computeActionKey(action);
  }

  private ActionKey fingerprintToActionKey(KeyBytesProvider fingerprint) {
    return fingerprintToActionKey(fingerprint, digestUtil, SKYCACHE_PLATFORM);
  }

  private Action fingerprintToAction(KeyBytesProvider fingerprint) {
    String hexHash = HashCode.fromBytes(fingerprint.toBytes()).toString();
    Digest inputRootDigest = DigestUtil.buildDigest(hexHash, 0);
    Digest commandDigest = digestUtil.compute(DUMMY_COMMAND);
    return Action.newBuilder()
        .setInputRootDigest(inputRootDigest)
        .setCommandDigest(commandDigest)
        .setPlatform(SKYCACHE_PLATFORM)
        .build();
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    logger.atInfo().log("put: key = %s, size = %d", fingerprint, serializedBytes.length);
    SettableWriteStatus writeStatus = new SettableWriteStatus();

    Digest valueDigest = digestUtil.compute(serializedBytes);
    Action action = fingerprintToAction(fingerprint);
    Digest actionDigest = digestUtil.compute(action);
    ActionKey actionKey = digestUtil.asActionKey(actionDigest);
    Digest commandDigest = digestUtil.compute(DUMMY_COMMAND);

    // 1. Upload value to CAS
    ListenableFuture<Void> uploadValueFuture =
        remoteCacheClient.uploadBlob(
            context, valueDigest, ByteString.copyFrom(serializedBytes), /* force= */ false);

    // 2. Upload Action to CAS
    ListenableFuture<Void> uploadActionFuture =
        remoteCacheClient.uploadBlob(
            context, actionDigest, action.toByteString(), /* force= */ false);

    // 3. Upload Command to CAS
    ListenableFuture<Void> uploadCommandFuture =
        remoteCacheClient.uploadBlob(
            context, commandDigest, DUMMY_COMMAND.toByteString(), /* force= */ false);

    // Combine CAS uploads
    ListenableFuture<Void> uploadsFuture =
        Futures.whenAllSucceed(uploadValueFuture, uploadActionFuture, uploadCommandFuture)
            .call(() -> null, MoreExecutors.directExecutor());

    // 3. Upload ActionResult to AC
    ListenableFuture<Void> uploadAcFuture =
        Futures.transformAsync(
            uploadsFuture,
            unused -> {
              ActionResult actionResult =
                  ActionResult.newBuilder()
                      .addOutputFiles(
                          OutputFile.newBuilder()
                              .setPath("entry")
                              .setDigest(valueDigest)
                              .setIsExecutable(false)
                              .build())
                      .build();
              return remoteCacheClient.uploadActionResult(context, actionKey, actionResult);
            },
            MoreExecutors.directExecutor());

    // Propagate result to writeStatus
    Futures.addCallback(
        uploadAcFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            logger.atInfo().log("put success: key = %s", fingerprint);
            writeStatus.markSuccess();
          }

          @Override
          public void onFailure(Throwable t) {
            logger.atWarning().withCause(t).log("put failed: key = %s", fingerprint);
            writeStatus.failWith(t);
          }
        },
        MoreExecutors.directExecutor());

    return writeStatus;
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    logger.atInfo().log("get: key = %s", fingerprint);
    ActionKey actionKey = fingerprintToActionKey(fingerprint);

    ListenableFuture<ActionResult> acFuture =
        remoteCacheClient.downloadActionResult(
            context, actionKey, /* inlineOutErr= */ false, /* inlineOutputFiles= */ java.util.Set.of());

    Futures.addCallback(
        acFuture,
        new FutureCallback<ActionResult>() {
          @Override
          public void onSuccess(ActionResult result) {}

          @Override
          public void onFailure(Throwable t) {
            logger.atWarning().withCause(t).log("downloadActionResult failed for key = %s", fingerprint);
          }
        },
        MoreExecutors.directExecutor());

    return Futures.transformAsync(
        acFuture,
        actionResult -> {
          if (actionResult == null) {
            logger.atInfo().log("get AC miss: key = %s", fingerprint);
            return Futures.immediateFuture(null);
          }

          OutputFile entryFile = null;
          for (OutputFile file : actionResult.getOutputFilesList()) {
            if ("entry".equals(file.getPath())) {
              entryFile = file;
              break;
            }
          }

          if (entryFile == null) {
            logger.atWarning().log("get AC hit but missing 'entry' file: key = %s", fingerprint);
            return Futures.immediateFuture(null);
          }

          ByteArrayOutputStream out =
              new ByteArrayOutputStream((int) entryFile.getDigest().getSizeBytes());
          ListenableFuture<Void> downloadFuture =
              remoteCacheClient.downloadBlob(context, entryFile.getDigest(), out);

          return Futures.catchingAsync(
              Futures.transform(
                  downloadFuture,
                  unused -> {
                    logger.atInfo().log("get CAS hit: key = %s", fingerprint);
                    return out.toByteArray();
                  },
                  MoreExecutors.directExecutor()),
              CacheNotFoundException.class,
              (e) -> {
                logger.atWarning().withCause(e).log(
                    "get CAS miss (evicted?): key = %s", fingerprint);
                return Futures.immediateFuture(null);
              },
              MoreExecutors.directExecutor());
        },
        MoreExecutors.directExecutor());
  }
}
