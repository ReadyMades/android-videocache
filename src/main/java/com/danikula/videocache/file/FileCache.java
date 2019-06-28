package com.danikula.videocache.file;

import com.danikula.videocache.Cache;
import com.danikula.videocache.ProxyCacheException;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * {@link Cache} that uses file for storing data.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class FileCache implements Cache {

  public  File             file;
  private RandomAccessFile dataFile;
  private FileRange        fileRange;

  private volatile long completeSize = -1;

  public FileCache(File file, FileRange fileRange) throws ProxyCacheException {
    try {
      this.fileRange = fileRange;
      File directory = file.getParentFile();
      Files.makeDir(directory);
      this.file = file;
      this.dataFile = new RandomAccessFile(this.file, "rw");
    } catch (IOException e) {
      throw new ProxyCacheException("Error using file " + file + " as disc cache", e);
    }
  }

  @Override
  public synchronized long available() throws ProxyCacheException {
    try {
      return (int) dataFile.length();
    } catch (IOException e) {
      throw new ProxyCacheException("Error reading length of file " + file, e);
    }
  }

  @Override
  public synchronized int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
    try {
      dataFile.seek(offset);
      return dataFile.read(buffer, 0, length);
    } catch (IOException e) {
      String
          format
          = "Error reading %d bytes with offset %d from file[%d bytes] to buffer[%d bytes]";
      throw new ProxyCacheException(String.format(format,
                                                  length,
                                                  offset,
                                                  available(),
                                                  buffer.length), e);
    }
  }

  @Override
  public synchronized void append(byte[] data, int length, long offset) throws ProxyCacheException {
    try {
      if (isCompleted()) {
        throw new ProxyCacheException("Error append cache: cache file " + file + " is completed!");
      }
      this.dataFile.seek(offset);
      this.dataFile.write(data, 0, length);
      this.fileRange.length(offset);
    } catch (IOException e) {
      String format = "Error writing %d bytes to %s from buffer with size %d";
      throw new ProxyCacheException(String.format(format, length, dataFile, data.length), e);
    }
  }

  @Override
  public synchronized void close() throws ProxyCacheException {
    try {
      dataFile.close();
    } catch (IOException e) {
      throw new ProxyCacheException("Error closing file " + file, e);
    }
  }

  @Override
  public synchronized void complete() throws ProxyCacheException {
    if (isCompleted()) {
      close();
    }
  }

  @Override
  public synchronized boolean isCompleted() {
    try {
      return offset() == 0 && available() >= completeSize;
    } catch (ProxyCacheException e) {
      e.printStackTrace();
    }
    return false;
  }

  @Override
  public synchronized long offset() {
    return fileRange.offset();
  }

  /**
   * Returns file to be used fo caching. It may as original file passed in constructor as some
   * temp file for not completed cache.
   *
   * @return file for caching.
   */
  public File getFile() {
    return file;
  }

  public void setSize(long size) {
    this.completeSize = size;
  }

  public synchronized void offset(long offset) {
    this.fileRange.offset(offset);
    try {
      dataFile.setLength(0);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public boolean inRange(long offset) {
    return fileRange.inRange(offset);
  }
}
