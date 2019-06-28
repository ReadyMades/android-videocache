package com.danikula.videocache.file;

/**
 * Created by magic on 7/14/17.
 */

public class FileRange {
  private long offset;
  private long length;

  public long offset() {
    return offset;
  }

  public long length() {
    return length;
  }

  public void offset(long offset) {
    this.offset = offset;
  }

  public void length(long length) {
    this.length = length;
  }

  public boolean inRange(long offset) {
    return offset < length() && offset >= offset();
  }

  public void clear() {
    this.length = 0;
    this.offset = 0;
  }
}
