package com.danikula.videocache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Proxy for {@link Source} with caching support ({@link Cache}).
 * <p/>
 * Can be used only for sources with persistent data (that doesn't change with time).
 * Method {@link #read(byte[], long, int)} will be blocked while fetching data from source.
 * Useful for streaming something with caching e.g. streaming video/audio etc.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class ProxyCache {

  private static final Logger LOG                      = LoggerFactory.getLogger("ProxyCache");
  private static final int    MAX_READ_SOURCE_ATTEMPTS = 4; 	// изменил с 1 на 4, чтобы уменьшить вероятность случайного падения при смене сети

  private final Source source;
  private final Cache  cache;
  private final   Object wc       = new Object();
  protected final Object stopLock = new Object();
  private final    AtomicInteger readSourceErrorsCount;
  private volatile Thread        sourceReaderThread;
  private volatile boolean       stopped;
  private volatile int percentsAvailable = -1;

  protected GetRequest request;
  protected Socket     socket;

  public ProxyCache(Source source, Cache cache) {
    this.source = checkNotNull(source);
    this.cache = checkNotNull(cache);
    this.readSourceErrorsCount = new AtomicInteger();
  }

  public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
    ProxyCacheUtils.assertBuffer(buffer, offset, length);

    waitData(offset, length);
    int read = cache.read(buffer, offset, length);
    if (cache.isCompleted() && percentsAvailable != 100) {
      percentsAvailable = 100;
      onCachePercentsAvailableChanged(100);
    }
    return read;
  }

  protected void waitData(long offset, long length) throws ProxyCacheException {
    while (cache.available() < (offset + length)
           && offset >= cache.offset()
           && cache.available() != source.length()
           && !isStopped()) {

      readSourceAsync(offset);
      waitForSourceData();
      waitReadIfNeeded();
      checkReadSourceErrorsCount();
    }
  }

  private void checkReadSourceErrorsCount() throws ProxyCacheException {
    int errorsCount = readSourceErrorsCount.get();
    if (errorsCount >= MAX_READ_SOURCE_ATTEMPTS) {
      readSourceErrorsCount.set(0);
      shutdown();
      throw new ProxyCacheException("Error reading source " + errorsCount + " times");
    }
  }

  public void shutdown() {
    synchronized (stopLock) {
      LOG.debug("Shutdown proxy for " + source);
      try {
        stopped = true;
        if (sourceReaderThread != null) {
          sourceReaderThread.interrupt();
        }
        cache.close();
      } catch (ProxyCacheException e) {
        onError(e);
      }
    }
  }

  public void stopThread() {
    synchronized (stopLock) {
      LOG.debug("Shutdown proxy for " + source);
      if (sourceReaderThread != null && sourceReaderThread.getState() != Thread.State.TERMINATED) {
        stopped = true;
      }
    }
  }

  public void resumeThread() {
    synchronized (stopLock) {
      stopped = false;
    }
  }

  protected void waitInterrupt() {
    if (sourceReaderThread != null) {
      while (sourceReaderThread.getState() != Thread.State.TERMINATED) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }

  protected synchronized void readSourceAsync(long offset)  {
    boolean readingInProgress = sourceReaderThread != null &&
                                sourceReaderThread.getState() != Thread.State.TERMINATED;
    if (!stopped && !cache.isCompleted() && !readingInProgress) {
      sourceReaderThread = new Thread(new SourceReaderRunnable(offset), "Source reader for " +
                                                                        source);
      sourceReaderThread.start();
    }
  }

  protected void waitReadIfNeeded() throws ProxyCacheException {
    if (readSourceErrorsCount.get() > 0) {
      try {
        TimeUnit.SECONDS.sleep(1);
      } catch (InterruptedException e) {
        throw new ProxyCacheException("Waiting source data is interrupted!", e);
      }
    }
  }

  protected void waitForSourceData() throws ProxyCacheException {
    synchronized (wc) {
      try {
        wc.wait(1000);
      } catch (InterruptedException e) {
        throw new ProxyCacheException("Waiting source data is interrupted!", e);
      }
    }
  }

  private void notifyNewCacheDataAvailable(long cacheAvailable, long sourceAvailable) {
    onCacheAvailable(cacheAvailable, sourceAvailable);

    synchronized (wc) {
      wc.notifyAll();
    }
  }

  protected void onCacheAvailable(long cacheAvailable, long sourceLength) {
    boolean zeroLengthSource = sourceLength == 0;
    int percents = zeroLengthSource ?
                   100 :
                   (int) ((float) cacheAvailable / sourceLength * 100);
    boolean percentsChanged   = percents != percentsAvailable;
    boolean sourceLengthKnown = sourceLength >= 0;
    if (sourceLengthKnown && percentsChanged) {
      onCachePercentsAvailableChanged(percents);
    }
    percentsAvailable = percents;
  }

  protected void onCachePercentsAvailableChanged(int percentsAvailable) {
  }

  protected void readSource(long from) {
    boolean sourceReaderRestarted = false;
    long sourceAvailable = -1;
    long offset          = 0;
    try {
      offset = from;
      source.open(offset);
      sourceAvailable = source.length();
      byte[] buffer = new byte[ProxyCacheUtils.DEFAULT_BUFFER_SIZE];
      int    readBytes;
      while ((readBytes = source.read(buffer)) != -1) {
        synchronized (stopLock) {
          if (isStopped()) {
            return;
          }
          cache.append(buffer, readBytes, offset);
        }
        offset += readBytes;
        notifyNewCacheDataAvailable(offset, sourceAvailable);
      }
      // tryComplete();
      onSourceRead();
    } catch (Throwable e) {
      readSourceErrorsCount.incrementAndGet();
      onError(e);
      /*
      Когда падает соединенине на https вылетает SSLException, на http - SocketException
      При этом пытаемся рестартануть выкачивание в кэш. При этом на случай, если идет
      переключение на mobile network, подождем 10 секунд, чтобы сети успели переключиться
      При следующем падении, если сети нет, вылетит UnknownHostException и мы остановим читающий
      поток, если сеть есть - не упадет.
       */
      if (e.getCause() != null &&
          (e.getCause() instanceof SSLException || e.getCause() instanceof SocketException)) {
        //waiting in case of switching wi-fi to mobile network
        try {
          TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
        readSource(offset);
        sourceReaderRestarted = true;
        return;
      }
      if (e.getCause() instanceof IOException) {
        stopThread();
      }
    } finally {
      if (sourceReaderRestarted) {
        closeSource();
      } else {
        notifyNewCacheDataAvailable(offset, sourceAvailable);
      }
    }
  }

  private void onSourceRead() {
    // guaranteed notify listeners after source read and cache completed
    percentsAvailable = 100;
    onCachePercentsAvailableChanged(percentsAvailable);
  }

  private void tryComplete() throws ProxyCacheException {
    synchronized (stopLock) {
      if (!isStopped() && cache.available() == source.length()) {
        cache.complete();
      }
    }
  }

  protected boolean isStopped() {
    return Thread.currentThread().isInterrupted() || stopped;
  }

  protected void closeSource() {
    try {
      source.close();
    } catch (ProxyCacheException e) {
      onError(new ProxyCacheException("Error closing source " + source, e));
    }
  }

  protected void onError(final Throwable e) {
    boolean interruption = e instanceof InterruptedProxyCacheException;
    if (interruption) {
      LOG.debug("ProxyCache is interrupted");
    } else {
      LOG.error("ProxyCache error", e);
    }
  }

  private class SourceReaderRunnable implements Runnable {

    private long offset;

    public SourceReaderRunnable(long offset) {
      this.offset = offset;
    }

    @Override
    public void run() {
      readSource(offset);
    }
  }
}
