package com.danikula.videocache;

import android.text.TextUtils;

import com.danikula.videocache.file.FileCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Locale;

import javax.net.ssl.SSLContext;

import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;

/**
 * Created by magic on 7/11/17.
 */

public class HttpProxyCachePartial extends ProxyCache {

  private       HttpUrlSource source;
  private final FileCache     cache;
  private       CacheListener listener;

  public HttpProxyCachePartial(HttpUrlSource source, FileCache cache) {
    super(source, cache);
    this.cache = cache;
    this.source = source;
    try {
      this.cache.setSize(this.source.length());
    } catch (ProxyCacheException e) {
      e.printStackTrace();
    }
  }

  public void registerCacheListener(CacheListener cacheListener) {
    this.listener = cacheListener;
  }

  public void processRequest(
      GetRequest request,
      Socket socket,
      int timeout,
      SSLContext sslContext)
      throws IOException, ProxyCacheException {

    OutputStream out             = new BufferedOutputStream(socket.getOutputStream());
    String       responseHeaders = newResponseHeaders(request);
    out.write(responseHeaders.getBytes("UTF-8"));

    long    offset   = request.rangeOffset;
    boolean isCached = isUseCache(request);
    this.cache.setSize(this.source.length());

    if (isCached) {
      responseWithCache(out, offset);
    } else {
      responseWithoutCache(out, offset, timeout, sslContext);
    }
  }

  private boolean isUseCache(GetRequest request) throws ProxyCacheException {

    long    sourceLength      = source.length();
    boolean sourceLengthKnown = sourceLength > 0;
    return !sourceLengthKnown ||
           !request.partial ||
           cache.inRange(request.rangeOffset);
  }

  private String newResponseHeaders(GetRequest request) throws IOException, ProxyCacheException {

    String  mime          = source.getMime();
    boolean mimeKnown     = !TextUtils.isEmpty(mime);
    long    length        = cache.isCompleted() ? cache.available() : source.length();
    boolean lengthKnown   = length >= 0;
    long    contentLength = request.partial ? length - request.rangeOffset : length;
    boolean addRange      = lengthKnown && request.partial;
    return new StringBuilder()
        .append(request.partial ? "HTTP/1.1 206 PARTIAL CONTENT\n" : "HTTP/1.1 200 OK\n")
        .append("Accept-Ranges: bytes\n")
        .append(lengthKnown ? format("Content-Length: %d\n", contentLength) : "")
        .append(addRange ?
                format("Content-Range: bytes %d-%d/%d\n", request.rangeOffset, length - 1, length) :
                "")
        .append(mimeKnown ? format("Content-Type: %s\n", mime) : "")
        .append("\n") // headers end
        .toString();
  }

  private void responseWithCache(OutputStream out, long offset)
      throws ProxyCacheException, IOException {

    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    int    readBytes;

    while ((readBytes = read(buffer, offset, buffer.length)) != -1) {
      out.write(buffer, 0, readBytes);
      offset += readBytes;
    }
    out.flush();
  }

  private void responseWithoutCache(OutputStream out, long offset, int timeout, SSLContext sslContext)
      throws ProxyCacheException, IOException {

    HttpUrlSource newSourceNoCache = new HttpUrlSource(this.source, timeout, sslContext);

    stopThread();
    source.close();
    source = newSourceNoCache;
    cache.offset(offset);
    waitInterrupt();
    resumeThread();
    readSourceAsync(offset);
    responseWithCache(out, offset);
  }

  private String format(String pattern, Object... args) {
    return String.format(Locale.US, pattern, args);
  }

  @Override
  protected void onCachePercentsAvailableChanged(int percents) {
    if (listener != null) {
      listener.onCacheAvailable(cache.file, source.getUrl(), percents);
    }
  }
}
