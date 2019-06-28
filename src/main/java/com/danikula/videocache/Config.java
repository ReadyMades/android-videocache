package com.danikula.videocache;

import java.io.File;
import javax.net.ssl.SSLContext;

import com.danikula.videocache.file.DiskUsage;
import com.danikula.videocache.file.FileNameGenerator;
import com.danikula.videocache.file.FileRange;
import com.danikula.videocache.headers.HeaderInjector;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;

/**
 * Configuration for proxy cache.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class Config {

  public final File              cacheRoot;
  public final FileNameGenerator fileNameGenerator;
  public final DiskUsage         diskUsage;
  public final SourceInfoStorage sourceInfoStorage;
  public final HeaderInjector    headerInjector;
  public final FileRange         fileRange;
  public final int               timeout;
  public final SSLContext        sslContext;

  Config(
      File cacheRoot,
      FileNameGenerator fileNameGenerator,
      DiskUsage diskUsage,
      SourceInfoStorage sourceInfoStorage,
      HeaderInjector headerInjector,
      int timeout, SSLContext context) {

    this.cacheRoot = cacheRoot;
    this.fileNameGenerator = fileNameGenerator;
    this.diskUsage = diskUsage;
    this.sourceInfoStorage = sourceInfoStorage;
    this.headerInjector = headerInjector;
    sslContext = context;
    this.fileRange = new FileRange();
    this.timeout = timeout;
  }

  File generateCacheFile(String url) {
    String name = fileNameGenerator.generate(url);
    return new File(cacheRoot, name);
  }
}
