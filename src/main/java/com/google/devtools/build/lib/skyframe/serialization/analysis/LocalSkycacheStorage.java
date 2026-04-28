package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Basic storage for Skycache local disk cache. */
public final class LocalSkycacheStorage {

  private final Path root;
  private final Path tmpRoot;

  public LocalSkycacheStorage(Path root) throws IOException {
    this.root = root;
    this.tmpRoot = root.getChild("tmp");
    root.createDirectoryAndParents();
    tmpRoot.createDirectoryAndParents();
  }

  /** Resolves a key to a path, using subdirectories to avoid folder limits. */
  private Path toPath(String key) {
    if (key.length() < 2) {
      return root.getChild(key);
    }
    return root.getChild(key.substring(0, 2)).getChild(key);
  }

  private Path getTempPath() {
    return tmpRoot.getChild(UUID.randomUUID().toString());
  }

  /** Saves data from an InputStream to a file with the given key. */
  public void save(String key, InputStream in) throws IOException {
    Path path = toPath(key);
    Path temp = getTempPath();

    try {
      try (OutputStream out = temp.getOutputStream()) {
        ByteStreams.copy(in, out);
        if (out instanceof FileOutputStream fos) {
          fos.getFD().sync();
        }
      }
      path.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.renameToleratingConcurrentCreation(temp, path);
    } catch (IOException e) {
      try {
        temp.delete();
      } catch (IOException deleteErr) {
        e.addSuppressed(deleteErr);
      }
      throw e;
    }
  }

  /** Reads data from the file with the given key into an OutputStream. */
  public void load(String key, OutputStream out) throws IOException {
    Path path = toPath(key);
    try (InputStream in = path.getInputStream()) {
      ByteStreams.copy(in, out);
    }
  }

  /** Checks if a file with the given key exists. */
  public boolean exists(String key) {
    return toPath(key).exists();
  }

  /** Deletes the file with the given key. */
  public void delete(String key) throws IOException {
    toPath(key).delete();
  }
}
