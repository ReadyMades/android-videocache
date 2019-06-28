package com.danikula.videocache;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

import android.text.TextUtils;
import com.danikula.videocache.headers.EmptyHeadersInjector;
import com.danikula.videocache.headers.HeaderInjector;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;
import com.danikula.videocache.sourcestorage.SourceInfoStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.danikula.videocache.Preconditions.checkNotNull;
import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;

/**
 * {@link Source} that uses http resource as source for {@link ProxyCache}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpUrlSource implements Source {

  private static final Logger LOG = LoggerFactory.getLogger("HttpUrlSource");

  private static final int MAX_REDIRECTS = 5;
  private final SourceInfoStorage sourceInfoStorage;
  private final HeaderInjector    headerInjector;
  private final SSLContext        sslContext;
  private       SourceInfo        sourceInfo;
  private       HttpURLConnection connection;
  private       InputStream       inputStream;
  private       int               timeout;

  public HttpUrlSource(String url) {
    this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
  }

  public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage) {
    this(url, sourceInfoStorage, new EmptyHeadersInjector(), 0, null);
  }

  public HttpUrlSource(
      String url,
      SourceInfoStorage sourceInfoStorage,
      HeaderInjector headerInjector,
      int timeout,
      SSLContext sslContext) {
    this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
    this.headerInjector = checkNotNull(headerInjector);
    this.sslContext = sslContext;
    SourceInfo sourceInfo = sourceInfoStorage.get(url);
    this.sourceInfo = sourceInfo != null ? sourceInfo :
                      new SourceInfo(url,
                                     Integer.MIN_VALUE,
                                     ProxyCacheUtils.getSupposablyMime(url));
    this.timeout = timeout;
  }

  public HttpUrlSource(HttpUrlSource source, int timeout, SSLContext sslContext) {
    this.sourceInfo = source.sourceInfo;
    this.sourceInfoStorage = source.sourceInfoStorage;
    this.headerInjector = source.headerInjector;
    this.timeout = timeout;
    this.sslContext = sslContext;
  }

  @Override
  public synchronized long length() throws ProxyCacheException {
    if (sourceInfo.length == Integer.MIN_VALUE) {
      fetchContentInfo();
    }
    return sourceInfo.length;
  }

  @Override
  public void open(long offset) throws ProxyCacheException {
    try {
      System.out.println();
      connection = openConnection(offset, timeout);
      String mime = connection.getContentType();
      inputStream = new BufferedInputStream(connection.getInputStream(), DEFAULT_BUFFER_SIZE);
      long length = readSourceAvailableBytes(connection, offset, connection.getResponseCode());
      this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
      this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
    } catch (IOException e) {
      throw new ProxyCacheException("Error opening connection for " +
                                    sourceInfo.url +
                                    " with offset " +
                                    offset, e);
    }
  }

  private long readSourceAvailableBytes(HttpURLConnection connection, long offset, int responseCode)
      throws IOException {
    long contentLength = getContentLength(connection);
    return responseCode == HTTP_OK ?
           contentLength
                                   :
           responseCode == HTTP_PARTIAL ? contentLength + offset : sourceInfo.length;
  }

  private long getContentLength(HttpURLConnection connection) {
    String contentLengthValue = connection.getHeaderField("Content-Length");
    return contentLengthValue == null ? -1 : Long.parseLong(contentLengthValue);
  }

  @Override
  public void close() throws ProxyCacheException {
    if (connection != null) {
      try {
        connection.disconnect();
      } catch (NullPointerException | IllegalArgumentException e) {
        String message = "Wait... but why? WTF!? " +
                         "Really shouldn't happen any more after fixing https://github" +
                         ".com/danikula/AndroidVideoCache/issues/43. " +
                         "If you read it on your device log, please, notify me danikula@gmail.com" +
                         " or create issue here " +
                         "https://github.com/danikula/AndroidVideoCache/issues.";
        throw new RuntimeException(message, e);
      } catch (ArrayIndexOutOfBoundsException e) {
        LOG.error("Error closing connection correctly. Should happen only on Android L. " +
                  "If anybody know how to fix it, please visit https://github" +
                  ".com/danikula/AndroidVideoCache/issues/88. " +
                  "Until good solution is not know, just ignore this issue :(", e);
      }
    }
  }

  @Override
  public int read(byte[] buffer) throws ProxyCacheException {
    if (inputStream == null) {
      throw new ProxyCacheException("Error reading data from " +
                                    sourceInfo.url +
                                    ": connection is absent!");
    }
    try {
      return inputStream.read(buffer, 0, buffer.length);
    } catch (InterruptedIOException e) {
      throw new InterruptedProxyCacheException("Reading source " +
                                               sourceInfo.url +
                                               " is interrupted", e);
    } catch (IOException e) {
      throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
    }
  }

  private void fetchContentInfo() throws ProxyCacheException {
    LOG.debug("Read content info from " + sourceInfo.url);
    HttpURLConnection urlConnection = null;
    InputStream       inputStream   = null;
    try {
      urlConnection = openConnection(0, timeout);
      long   length = getContentLength(urlConnection);
      String mime   = urlConnection.getContentType();
      inputStream = urlConnection.getInputStream();
      this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
      this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
      LOG.debug("Source info fetched: " + sourceInfo);
    } catch (IOException e) {
      LOG.error("Error fetching info from " + sourceInfo.url, e);
      throw new ProxyCacheException(e);
    } finally {
      ProxyCacheUtils.close(inputStream);
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
    }
  }

  private HttpURLConnection openConnection(long offset, int timeout)
      throws IOException, ProxyCacheException {
    HttpURLConnection connection;
    boolean           redirected;
    int               redirectCount = 0;
    String            url           = this.sourceInfo.url;
    do {
      //todo
      //check this https://github.com/openid/AppAuth-Android/pull/144/files
      //to check if the response code is kind of 400 and get error stream
      LOG.debug("Open connection " + (offset > 0 ? " with offset " + offset : "") + " to " + url);

      connection = (HttpURLConnection) new URL(url).openConnection();

      if (connection instanceof HttpsURLConnection && sslContext != null) {
        ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
      }

      injectCustomHeaders(connection, url);
      if (offset > 0) {
        connection.setRequestProperty("Range", "bytes=" + offset + "-");
      }
      if (timeout > 0) {
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
      }
      int code = connection.getResponseCode();
      redirected = code == HTTP_MOVED_PERM || code == HTTP_MOVED_TEMP || code == HTTP_SEE_OTHER;
      if (redirected) {
        url = connection.getHeaderField("Location");
        redirectCount++;
        connection.disconnect();
      }
      if (redirectCount > MAX_REDIRECTS) {
        throw new ProxyCacheException("Too many redirects: " + redirectCount);
      }
    } while (redirected);
    return connection;
  }

  private void injectCustomHeaders(HttpURLConnection connection, String url) {
    Map<String, String> extraHeaders = headerInjector.addHeaders(url);
    for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
      connection.setRequestProperty(header.getKey(), header.getValue());
    }
  }

  public synchronized String getMime() throws ProxyCacheException {
    if (TextUtils.isEmpty(sourceInfo.mime)) {
      fetchContentInfo();
    }
    return sourceInfo.mime;
  }

  public String getUrl() {
    return sourceInfo.url;
  }

  @Override
  public String toString() {
    return "HttpUrlSource{sourceInfo='" + sourceInfo + "}";
  }
}
