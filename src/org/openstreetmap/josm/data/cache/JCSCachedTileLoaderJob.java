// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.cache;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.jcs.access.behavior.ICacheAccess;
import org.apache.commons.jcs.engine.behavior.ICacheElement;
import org.openstreetmap.gui.jmapviewer.FeatureAdapter;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.cache.ICachedLoaderListener.LoadResult;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.tools.Utils;

/**
 * @author Wiktor Niesiobędzki
 *
 * @param <K> cache entry key type
 * @param <V> cache value type
 *
 * Generic loader for HTTP based tiles. Uses custom attribute, to check, if entry has expired
 * according to HTTP headers sent with tile. If so, it tries to verify using Etags
 * or If-Modified-Since / Last-Modified.
 *
 * If the tile is not valid, it will try to download it from remote service and put it
 * to cache. If remote server will fail it will try to use stale entry.
 *
 * This class will keep only one Job running for specified tile. All others will just finish, but
 * listeners will be gathered and notified, once download job will be finished
 *
 * @since 8168
 */
public abstract class JCSCachedTileLoaderJob<K, V extends CacheEntry> implements ICachedLoaderJob<K>, Runnable {
    private static final Logger log = FeatureAdapter.getLogger(JCSCachedTileLoaderJob.class.getCanonicalName());
    protected static final long DEFAULT_EXPIRE_TIME = 1000L * 60 * 60 * 24 * 7; // 7 days
    // Limit for the max-age value send by the server.
    protected static final long EXPIRE_TIME_SERVER_LIMIT = 1000L * 60 * 60 * 24 * 28; // 4 weeks
    // Absolute expire time limit. Cached tiles that are older will not be used,
    // even if the refresh from the server fails.
    protected static final long ABSOLUTE_EXPIRE_TIME_LIMIT = 1000L * 60 * 60 * 24 * 365; // 1 year

    /**
     * maximum download threads that will be started
     */
    public static final IntegerProperty THREAD_LIMIT = new IntegerProperty("cache.jcs.max_threads", 10);

    /*
     * ThreadPoolExecutor starts new threads, until THREAD_LIMIT is reached. Then it puts tasks into LinkedBlockingDeque.
     *
     * The queue works FIFO, so one needs to take care about ordering of the entries submitted
     *
     * There is no point in canceling tasks, that are already taken by worker threads (if we made so much effort, we can at least cache
     * the response, so later it could be used). We could actually cancel what is in LIFOQueue, but this is a tradeoff between simplicity
     * and performance (we do want to have something to offer to worker threads before tasks will be resubmitted by class consumer)
     */

    private static ThreadPoolExecutor DEFAULT_DOWNLOAD_JOB_DISPATCHER = new ThreadPoolExecutor(
            2, // we have a small queue, so threads will be quickly started (threads are started only, when queue is full)
            THREAD_LIMIT.get().intValue(), // do not this number of threads
            30, // keepalive for thread
            TimeUnit.SECONDS,
            // make queue of LIFO type - so recently requested tiles will be loaded first (assuming that these are which user is waiting to see)
            new LinkedBlockingDeque<Runnable>(),
            Utils.newThreadFactory("JCS-downloader-%d", Thread.NORM_PRIORITY)
            );



    private static ConcurrentMap<String, Set<ICachedLoaderListener>> inProgress = new ConcurrentHashMap<>();
    private static ConcurrentMap<String, Boolean> useHead = new ConcurrentHashMap<>();

    protected long now; // when the job started

    private ICacheAccess<K, V> cache;
    private ICacheElement<K, V> cacheElement;
    protected V cacheData;
    protected CacheEntryAttributes attributes;

    // HTTP connection parameters
    private int connectTimeout;
    private int readTimeout;
    private Map<String, String> headers;
    private ThreadPoolExecutor downloadJobExecutor;
    private Runnable finishTask;
    private boolean force;

    /**
     * @param cache cache instance that we will work on
     * @param headers HTTP headers to be sent together with request
     * @param readTimeout when connecting to remote resource
     * @param connectTimeout when connecting to remote resource
     * @param downloadJobExecutor that will be executing the jobs
     */
    public JCSCachedTileLoaderJob(ICacheAccess<K, V> cache,
            int connectTimeout, int readTimeout,
            Map<String, String> headers,
            ThreadPoolExecutor downloadJobExecutor) {

        this.cache = cache;
        this.now = System.currentTimeMillis();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.headers = headers;
        this.downloadJobExecutor = downloadJobExecutor;
    }

    /**
     * @param cache cache instance that we will work on
     * @param headers HTTP headers to be sent together with request
     * @param readTimeout when connecting to remote resource
     * @param connectTimeout when connecting to remote resource
     */
    public JCSCachedTileLoaderJob(ICacheAccess<K, V> cache,
            int connectTimeout, int readTimeout,
            Map<String, String> headers) {
        this(cache, connectTimeout, readTimeout,
                headers, DEFAULT_DOWNLOAD_JOB_DISPATCHER);
    }

    private void ensureCacheElement() {
        if (cacheElement == null && getCacheKey() != null) {
            cacheElement = cache.getCacheElement(getCacheKey());
            if (cacheElement != null) {
                attributes = (CacheEntryAttributes) cacheElement.getElementAttributes();
                cacheData = cacheElement.getVal();
            }
        }
    }

    public V get() {
        ensureCacheElement();
        return cacheData;
    }

    @Override
    public void submit(ICachedLoaderListener listener, boolean force) throws IOException {
        this.force = force;
        boolean first = false;
        URL url = getUrl();
        String deduplicationKey = null;
        if (url != null) {
            // url might be null, for example when Bing Attribution is not loaded yet
            deduplicationKey = url.toString();
        }
        if (deduplicationKey == null) {
            log.log(Level.WARNING, "No url returned for: {0}, skipping", getCacheKey());
            throw new IllegalArgumentException("No url returned");
        }
        synchronized (inProgress) {
            Set<ICachedLoaderListener> newListeners = inProgress.get(deduplicationKey);
            if (newListeners == null) {
                newListeners = new HashSet<>();
                inProgress.put(deduplicationKey, newListeners);
                first = true;
            }
            newListeners.add(listener);
        }

        if (first || force) {
            // submit all jobs to separate thread, so calling thread is not blocked with IO when loading from disk
            downloadJobExecutor.execute(this);
        }
    }

    /**
     * This method is run when job has finished
     */
    protected void executionFinished() {
        if (finishTask != null) {
            finishTask.run();
        }
    }

    /**
     *
     * @return checks if object from cache has sufficient data to be returned
     */
    protected boolean isObjectLoadable() {
        if (cacheData == null) {
            return false;
        }
        byte[] content = cacheData.getContent();
        return content != null && content.length > 0;
    }

    /**
     * Simple implementation. All errors should be cached as empty. Though some JDK (JDK8 on Windows for example)
     * doesn't return 4xx error codes, instead they do throw an FileNotFoundException or IOException
     *
     * @return true if we should put empty object into cache, regardless of what remote resource has returned
     */
    protected boolean cacheAsEmpty() {
        return attributes.getResponseCode() < 500;
    }

    /**
     * @return key under which discovered server settings will be kept
     */
    protected String getServerKey() {
        return getUrlNoException().getHost();
    }

    @Override
    public void run() {
        final Thread currentThread = Thread.currentThread();
        final String oldName = currentThread.getName();
        currentThread.setName("JCS Downloading: " + getUrlNoException());
        ensureCacheElement();
        try {
            // try to fetch from cache
            if (!force && cacheElement != null && isCacheElementValid() && isObjectLoadable()) {
                // we got something in cache, and it's valid, so lets return it
                log.log(Level.FINE, "JCS - Returning object from cache: {0}", getCacheKey());
                finishLoading(LoadResult.SUCCESS);
                return;
            }

            // try to load object from remote resource
            if (loadObject()) {
                finishLoading(LoadResult.SUCCESS);
            } else {
                // if loading failed - check if we can return stale entry
                if (isObjectLoadable()) {
                    // try to get stale entry in cache
                    finishLoading(LoadResult.SUCCESS);
                    log.log(Level.FINE, "JCS - found stale object in cache: {0}", getUrlNoException());
                } else {
                    // failed completely
                    finishLoading(LoadResult.FAILURE);
                }
            }
        } finally {
            executionFinished();
            currentThread.setName(oldName);
        }
    }

    private void finishLoading(LoadResult result) {
        Set<ICachedLoaderListener> listeners = null;
        synchronized (inProgress) {
            listeners = inProgress.remove(getUrlNoException().toString());
        }
        if (listeners == null) {
            log.log(Level.WARNING, "Listener not found for URL: {0}. Listener not notified!", getUrlNoException());
            return;
        }
        for (ICachedLoaderListener l: listeners) {
            l.loadingFinished(cacheData, attributes, result);
        }
    }

    protected boolean isCacheElementValid() {
        long expires = attributes.getExpirationTime();

        // check by expire date set by server
        if (expires != 0L) {
            // put a limit to the expire time (some servers send a value
            // that is too large)
            expires = Math.min(expires, attributes.getCreateTime() + EXPIRE_TIME_SERVER_LIMIT);
            if (now > expires) {
                log.log(Level.FINE, "JCS - Object {0} has expired -> valid to {1}, now is: {2}",
                        new Object[]{getUrlNoException(), Long.toString(expires), Long.toString(now)});
                return false;
            }
        } else if (attributes.getLastModification() > 0 &&
                now - attributes.getLastModification() > DEFAULT_EXPIRE_TIME) {
            // check by file modification date
            log.log(Level.FINE, "JCS - Object has expired, maximum file age reached {0}", getUrlNoException());
            return false;
        } else if (now - attributes.getCreateTime() > DEFAULT_EXPIRE_TIME) {
            log.log(Level.FINE, "JCS - Object has expired, maximum time since object creation reached {0}", getUrlNoException());
            return false;
        }
        return true;
    }

    /**
     * @return true if object was successfully downloaded, false, if there was a loading failure
     */

    private boolean loadObject() {
        if (attributes == null) {
            attributes = new CacheEntryAttributes();
        }
        try {
            // if we have object in cache, and host doesn't support If-Modified-Since nor If-None-Match
            // then just use HEAD request and check returned values
            if (isObjectLoadable() &&
                    Boolean.TRUE.equals(useHead.get(getServerKey())) &&
                    isCacheValidUsingHead()) {
                log.log(Level.FINE, "JCS - cache entry verified using HEAD request: {0}", getUrl());
                return true;
            }

            HttpURLConnection urlConn = getURLConnection(getUrl());

            if (isObjectLoadable()  &&
                    (now - attributes.getLastModification()) <= ABSOLUTE_EXPIRE_TIME_LIMIT) {
                urlConn.setIfModifiedSince(attributes.getLastModification());
            }
            if (isObjectLoadable() && attributes.getEtag() != null) {
                urlConn.addRequestProperty("If-None-Match", attributes.getEtag());
            }

            log.log(Level.INFO, "GET {0} -> {1}", new Object[]{getUrl(), urlConn.getResponseCode()});

            // follow redirects
            for (int i = 0; i < 5; i++) {
                if (urlConn.getResponseCode() == 302) {
                    urlConn = getURLConnection(new URL(urlConn.getHeaderField("Location")));
                } else {
                    break;
                }
            }
            if (urlConn.getResponseCode() == 304) {
                // If isModifiedSince or If-None-Match has been set
                // and the server answers with a HTTP 304 = "Not Modified"
                log.log(Level.FINE, "JCS - IfModifiedSince/Etag test: local version is up to date: {0}", getUrl());
                return true;
            } else if (isObjectLoadable() // we have an object in cache, but we haven't received 304 resposne code
                    && (
                            (attributes.getEtag() != null && attributes.getEtag().equals(urlConn.getRequestProperty("ETag"))) ||
                            attributes.getLastModification() == urlConn.getLastModified())
                    ) {
                // we sent ETag or If-Modified-Since, but didn't get 304 response code
                // for further requests - use HEAD
                String serverKey = getServerKey();
                log.log(Level.INFO, "JCS - Host: {0} found not to return 304 codes for If-Modifed-Since or If-None-Match headers",
                        serverKey);
                useHead.put(serverKey, Boolean.TRUE);
            }


            attributes = parseHeaders(urlConn);

            for (int i = 0; i < 5; ++i) {
                if (urlConn.getResponseCode() == 503) {
                    Thread.sleep(5000+(new Random()).nextInt(5000));
                    continue;
                }

                attributes.setResponseCode(urlConn.getResponseCode());
                byte[] raw;
                if (urlConn.getResponseCode() == 200) {
                    raw = Utils.readBytesFromStream(urlConn.getInputStream());
                } else {
                    raw = new byte[]{};
                }

                if (isResponseLoadable(urlConn.getHeaderFields(), urlConn.getResponseCode(), raw)) {
                    // we need to check cacheEmpty, so for cases, when data is returned, but we want to store
                    // as empty (eg. empty tile images) to save some space
                    cacheData = createCacheEntry(raw);
                    cache.put(getCacheKey(), cacheData, attributes);
                    log.log(Level.FINE, "JCS - downloaded key: {0}, length: {1}, url: {2}",
                            new Object[] {getCacheKey(), raw.length, getUrl()});
                    return true;
                } else if (cacheAsEmpty()) {
                    cacheData = createCacheEntry(new byte[]{});
                    cache.put(getCacheKey(), cacheData, attributes);
                    log.log(Level.FINE, "JCS - Caching empty object {0}", getUrl());
                    return true;
                } else {
                    log.log(Level.FINE, "JCS - failure during load - reponse is not loadable nor cached as empty");
                    return false;
                }
            }
        } catch (FileNotFoundException e) {
            log.log(Level.FINE, "JCS - Caching empty object as server returned 404 for: {0}", getUrlNoException());
            attributes.setResponseCode(404);
            attributes.setErrorMessage(e.toString());
            boolean doCache = isResponseLoadable(null, 404, null) || cacheAsEmpty();
            if (doCache) {
                cacheData = createCacheEntry(new byte[]{});
                cache.put(getCacheKey(), cacheData, attributes);
            }
            return doCache;
        } catch (IOException e) {
            log.log(Level.FINE, "JCS - IOExecption during communication with server for: {0}", getUrlNoException());
            attributes.setErrorMessage(e.toString());
            attributes.setResponseCode(499); // set dummy error code
            boolean doCache = isResponseLoadable(null, 499, null) || cacheAsEmpty(); //generic 499 error code returned
            if (doCache) {
                cacheData = createCacheEntry(new byte[]{});
                cache.put(getCacheKey(), createCacheEntry(new byte[]{}), attributes);
            }
            return doCache;
        } catch (Exception e) {
            attributes.setErrorMessage(e.toString());
            log.log(Level.WARNING, "JCS - Exception during download {0}",  getUrlNoException());
            Main.warn(e);
        }
        log.log(Level.WARNING, "JCS - Silent failure during download: {0}", getUrlNoException());
        return false;

    }

    /**
     * Check if the object is loadable. This means, if the data will be parsed, and if this response
     * will finish as successful retrieve.
     *
     * This simple implementation doesn't load empty response, nor client (4xx) and server (5xx) errors
     *
     * @param headerFields headers sent by server
     * @param responseCode http status code
     * @param raw data read from server
     * @return true if object should be cached and returned to listener
     */
    protected boolean isResponseLoadable(Map<String, List<String>> headerFields, int responseCode, byte[] raw) {
        if (raw == null || raw.length == 0 || responseCode >= 400) {
            return false;
        }
        return true;
    }

    protected abstract V createCacheEntry(byte[] content);

    protected CacheEntryAttributes parseHeaders(URLConnection urlConn) {
        CacheEntryAttributes ret = new CacheEntryAttributes();

        Long lng = urlConn.getExpiration();
        if (lng.equals(0L)) {
            try {
                String str = urlConn.getHeaderField("Cache-Control");
                if (str != null) {
                    for (String token: str.split(",")) {
                        if (token.startsWith("max-age=")) {
                            lng = Long.parseLong(token.substring(8)) * 1000 +
                                    System.currentTimeMillis();
                        }
                    }
                }
            } catch (NumberFormatException e) {
                // ignore malformed Cache-Control headers
                if (Main.isTraceEnabled()) {
                    Main.trace(e.getMessage());
                }
            }
        }

        ret.setExpirationTime(lng);
        ret.setLastModification(now);
        ret.setEtag(urlConn.getHeaderField("ETag"));
        return ret;
    }

    private HttpURLConnection getURLConnection(URL url) throws IOException {
        HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
        urlConn.setRequestProperty("Accept", "text/html, image/png, image/jpeg, image/gif, */*");
        urlConn.setReadTimeout(readTimeout); // 30 seconds read timeout
        urlConn.setConnectTimeout(connectTimeout);
        if (headers != null) {
            for (Map.Entry<String, String> e: headers.entrySet()) {
                urlConn.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        if (force) {
            urlConn.setUseCaches(false);
        }
        return urlConn;
    }

    private boolean isCacheValidUsingHead() throws IOException {
        HttpURLConnection urlConn = getURLConnection(getUrl());
        urlConn.setRequestMethod("HEAD");
        for (int i = 0; i < 5; i++) {
            if (urlConn.getResponseCode() == 302) {
                urlConn = getURLConnection(new URL(urlConn.getHeaderField("Location")));
            } else {
                break;
            }
        }
        long lastModified = urlConn.getLastModified();
        return (attributes.getEtag() != null && attributes.getEtag().equals(urlConn.getRequestProperty("ETag"))) ||
                (lastModified != 0 && lastModified <= attributes.getLastModification());
    }

    /**
     * TODO: move to JobFactory
     * cancels all outstanding tasks in the queue.
     */
    public void cancelOutstandingTasks() {
        for (Runnable r: downloadJobExecutor.getQueue()) {
            if (downloadJobExecutor.remove(r) && r instanceof JCSCachedTileLoaderJob) {
                ((JCSCachedTileLoaderJob<?, ?>) r).handleJobCancellation();
            }
        }
    }

    /**
     * Sets a job, that will be run, when job will finish execution
     * @param runnable that will be executed
     */
    public void setFinishedTask(Runnable runnable) {
        this.finishTask = runnable;

    }

    /**
     * Marks this job as canceled
     */
    public void handleJobCancellation() {
        finishLoading(LoadResult.CANCELED);
    }

    private URL getUrlNoException() {
        try {
            return getUrl();
        } catch (IOException e) {
            return null;
        }
    }
}
