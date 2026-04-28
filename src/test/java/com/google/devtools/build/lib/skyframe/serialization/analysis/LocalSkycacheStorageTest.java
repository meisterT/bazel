package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LocalSkycacheStorageTest {

  private FileSystem fs;
  private Path root;
  private LocalSkycacheStorage storage;

  @Before
  public void setUp() throws Exception {
    fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    root = fs.getPath("/root/skycache");
    storage = new LocalSkycacheStorage(root);
  }

  @Test
  public void constructor_createsDirectory() throws Exception {
    Path newRoot = fs.getPath("/root/new_skycache");
    assertThat(newRoot.exists()).isFalse();

    new LocalSkycacheStorage(newRoot);

    assertThat(newRoot.exists()).isTrue();
    assertThat(newRoot.getChild("tmp").exists()).isTrue();
  }

  @Test
  public void saveAndLoad_success() throws Exception {
    String key = "abcdef123456";
    String content = "hello world";
    InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

    storage.save(key, in);

    assertThat(storage.exists(key)).isTrue();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    storage.load(key, out);
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(content);
  }

  @Test
  public void save_atomicWrite() throws Exception {
    String key = "abcdef123456";
    String content = "hello world";
    InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

    storage.save(key, in);

    // Verify that the file is in the correct subdirectory.
    Path expectedPath = root.getChild("ab").getChild(key);
    assertThat(expectedPath.exists()).isTrue();
  }

  @Test
  public void load_fileNotFound() {
    String key = "nonexistent";
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThrows(IOException.class, () -> storage.load(key, out));
  }

  @Test
  public void delete_success() throws Exception {
    String key = "abcdef123456";
    String content = "hello world";
    InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

    storage.save(key, in);
    assertThat(storage.exists(key)).isTrue();

    storage.delete(key);
    assertThat(storage.exists(key)).isFalse();
  }

  @Test
  public void shortKey_storedInRoot() throws Exception {
    String key = "a";
    String content = "hello world";
    InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

    storage.save(key, in);

    Path expectedPath = root.getChild(key);
    assertThat(expectedPath.exists()).isTrue();
  }
}
