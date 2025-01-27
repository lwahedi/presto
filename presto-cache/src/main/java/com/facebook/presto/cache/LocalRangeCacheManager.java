/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.cache;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.spi.PrestoException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import io.airlift.slice.Slice;
import org.apache.hadoop.fs.Path;

import javax.annotation.PreDestroy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterators.getOnlyElement;
import static java.lang.StrictMath.toIntExact;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

// 3 major TODOs for this class:
// TODO: Make cache eviction based on cache size rather than file count; add evict count stats to CacheStats as well.
// TODO: Make cache state persistent on disk so we do not need to wipe out cache every time we reboot a server.
// TODO: File merge and inflight requests memory control.
@SuppressWarnings("UnstableApiUsage")
public class LocalRangeCacheManager
        implements CacheManager
{
    private static final Logger log = Logger.get(LocalRangeCacheManager.class);

    private static final String EXTENSION = ".cache";

    private final ExecutorService cacheFlushExecutor;
    private final ExecutorService cacheRemovalExecutor;

    // a mapping from remote file `F` to a range map `M`; the corresponding local cache file for each range in `M` represents the cached chunk of `F`
    private final Map<Path, CacheRange> persistedRanges = new ConcurrentHashMap<>();
    // a local cache only to control the lifecycle of persisted
    private final Cache<Path, Boolean> cache;

    // stats
    private final CacheStats stats;

    // config
    private final Path baseDirectory;
    private final long maxInflightBytes;

    public LocalRangeCacheManager(CacheConfig cacheConfig, CacheStats stats, ExecutorService cacheFlushExecutor, ExecutorService cacheRemovalExecutor)
    {
        requireNonNull(cacheConfig, "directory is null");
        this.cacheFlushExecutor = cacheFlushExecutor;
        this.cacheRemovalExecutor = cacheRemovalExecutor;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(cacheConfig.getMaxCachedEntries())
                .expireAfterAccess(cacheConfig.getCacheTtl().toMillis(), MILLISECONDS)
                .removalListener(new CacheRemovalListener())
                .recordStats()
                .build();
        this.stats = requireNonNull(stats, "stats is null");
        this.baseDirectory = new Path(cacheConfig.getBaseDirectory());
        checkArgument(cacheConfig.getMaxInMemoryCacheSize().toBytes() >= 0, "maxInflightBytes is negative");
        this.maxInflightBytes = cacheConfig.getMaxInMemoryCacheSize().toBytes();

        File target = new File(baseDirectory.toUri());
        if (!target.exists()) {
            try {
                Files.createDirectories(target.toPath());
            }
            catch (IOException e) {
                throw new PrestoException(GENERIC_INTERNAL_ERROR, "cannot create cache directory " + target, e);
            }
        }
        else {
            File[] files = target.listFiles();
            if (files == null) {
                return;
            }

            this.cacheRemovalExecutor.submit(() -> Arrays.stream(files).forEach(file -> {
                try {
                    Files.delete(file.toPath());
                }
                catch (IOException e) {
                    // ignore
                }
            }));
        }
    }

    @PreDestroy
    public void destroy()
    {
        cacheFlushExecutor.shutdownNow();
        cacheRemovalExecutor.shutdownNow();
    }

    @Override
    public boolean get(FileReadRequest request, byte[] buffer, int offset)
    {
        boolean result = read(request, buffer, offset);
        if (result) {
            stats.incrementCacheHit();
        }
        else {
            stats.incrementCacheMiss();
        }

        return result;
    }

    @Override
    public void put(FileReadRequest key, Slice data)
    {
        if (stats.getInMemoryRetainedBytes() + data.length() >= maxInflightBytes) {
            // cannot accept more requests
            return;
        }

        // make a copy given the input data could be a reusable buffer
        stats.addInMemoryRetainedBytes(data.length());
        byte[] copy = data.getBytes();
        cacheFlushExecutor.submit(() -> {
            Path newFilePath = new Path(baseDirectory.toUri() + "/" + randomUUID() + EXTENSION);
            if (!write(key, copy, newFilePath)) {
                log.warn("%s Fail to persist cache %s with length %s ", Thread.currentThread().getName(), newFilePath, key.getLength());
            }
            stats.addInMemoryRetainedBytes(-copy.length);
        });
    }

    private boolean read(FileReadRequest request, byte[] buffer, int offset)
    {
        if (request.getLength() <= 0) {
            // no-op
            return true;
        }

        try {
            // hint the cache no matter what
            cache.get(request.getPath(), () -> true);
        }
        catch (ExecutionException e) {
            // ignore
        }

        // check if the file is cached on local disk
        CacheRange cacheRange = persistedRanges.get(request.getPath());
        if (cacheRange == null) {
            return false;
        }

        LocalCacheFile cacheFile;
        Lock readLock = cacheRange.getLock().readLock();
        readLock.lock();
        try {
            Map<Range<Long>, LocalCacheFile> diskRanges = cacheRange.getRange().subRangeMap(Range.closedOpen(request.getOffset(), request.getLength() + request.getOffset())).asMapOfRanges();
            if (diskRanges.size() != 1) {
                // no range or there is a hole in between
                return false;
            }

            cacheFile = getOnlyElement(diskRanges.entrySet().iterator()).getValue();
        }
        finally {
            readLock.unlock();
        }

        try (RandomAccessFile file = new RandomAccessFile(new File(cacheFile.getPath().toUri()), "r")) {
            file.seek(request.getOffset() - cacheFile.getOffset());
            file.readFully(buffer, offset, request.getLength());
            return true;
        }
        catch (IOException e) {
            // there might be a chance the file has been deleted
            return false;
        }
    }

    private boolean write(FileReadRequest key, byte[] data, Path newFilePath)
    {
        Path targetFile = key.getPath();
        persistedRanges.putIfAbsent(targetFile, new CacheRange());

        LocalCacheFile previousCacheFile;
        LocalCacheFile followingCacheFile;

        CacheRange cacheRange = persistedRanges.get(targetFile);
        if (cacheRange == null) {
            // there is a chance the cache has just expired.
            return false;
        }

        Lock readLock = cacheRange.getLock().readLock();
        readLock.lock();
        try {
            RangeMap<Long, LocalCacheFile> cache = cacheRange.getRange();

            // check if it can be merged with the previous or following range
            previousCacheFile = cache.get(key.getOffset() - 1);
            followingCacheFile = cache.get(key.getOffset() + key.getLength());
        }
        finally {
            readLock.unlock();
        }

        if (previousCacheFile != null && cacheFileEquals(previousCacheFile, followingCacheFile)) {
            log.debug("%s found covered range %s", Thread.currentThread().getName(), previousCacheFile.getPath());
            // this range has already been covered by someone else
            return true;
        }

        long newFileOffset;
        int newFileLength;
        try {
            if (previousCacheFile == null) {
                // a new file
                Files.write((new File(newFilePath.toUri())).toPath(), data, CREATE_NEW);

                // update new range info
                newFileLength = data.length;
                newFileOffset = key.getOffset();
            }
            else {
                // copy previous file's data to the new file
                byte[] previousFileBytes = Files.readAllBytes(new File(previousCacheFile.getPath().toUri()).toPath());
                Files.write((new File(newFilePath.toUri())).toPath(), previousFileBytes, CREATE_NEW);
                int previousFileLength = previousFileBytes.length;
                long previousFileOffset = previousCacheFile.getOffset();

                // remove the overlapping part and append the remaining cache data
                byte[] remainingCacheFileBytes = new byte[toIntExact((key.getLength() + key.getOffset()) - (previousFileLength + previousFileOffset))];
                System.arraycopy(data, toIntExact(previousFileLength + previousFileOffset - key.getOffset()), remainingCacheFileBytes, 0, remainingCacheFileBytes.length);
                Files.write((new File(newFilePath.toUri())).toPath(), remainingCacheFileBytes, APPEND);

                // update new range info
                newFileLength = previousFileLength + remainingCacheFileBytes.length;
                newFileOffset = previousFileOffset;
            }

            if (followingCacheFile != null) {
                // remove the overlapping part and append the remaining following file data
                try (RandomAccessFile followingFile = new RandomAccessFile(new File(followingCacheFile.getPath().toUri()), "r")) {
                    byte[] remainingFollowingFileBytes = new byte[toIntExact((followingFile.length() + followingCacheFile.getOffset()) - (key.getLength() + key.getOffset()))];
                    followingFile.seek(key.getOffset() + key.getLength() - followingCacheFile.getOffset());
                    followingFile.readFully(remainingFollowingFileBytes, 0, remainingFollowingFileBytes.length);
                    Files.write((new File(newFilePath.toUri())).toPath(), remainingFollowingFileBytes, APPEND);

                    // update new range info
                    newFileLength += remainingFollowingFileBytes.length;
                }
            }
        }
        catch (IOException e) {
            log.warn(e, "%s encountered an error while flushing file %s", Thread.currentThread().getName(), newFilePath);
            tryDeleteFile(newFilePath);
            return false;
        }

        // use a flag so that file deletion can be done outside the lock
        boolean updated;
        Set<Path> cacheFilesToDelete = new HashSet<>();

        Lock writeLock = persistedRanges.get(targetFile).getLock().writeLock();
        writeLock.lock();
        try {
            RangeMap<Long, LocalCacheFile> cache = persistedRanges.get(targetFile).getRange();
            // check again if the previous or following range has been updated by someone else
            LocalCacheFile newPreviousCacheFile = cache.get(key.getOffset() - 1);
            LocalCacheFile newFollowingCacheFile = cache.get(key.getOffset() + key.getLength());

            if (!cacheFileEquals(previousCacheFile, newPreviousCacheFile) || !cacheFileEquals(followingCacheFile, newFollowingCacheFile)) {
                // someone else has updated the cache; delete the newly created file
                updated = false;
            }
            else {
                updated = true;

                // remove all the files that can be covered by the current range
                cacheFilesToDelete = cache.subRangeMap(Range.closedOpen(key.getOffset(), key.getOffset() + key.getLength())).asMapOfRanges().values().stream()
                        .map(LocalCacheFile::getPath).collect(Collectors.toSet());

                // update the range
                Range<Long> newRange = Range.closedOpen(newFileOffset, newFileOffset + newFileLength);
                cache.remove(newRange);
                cache.put(newRange, new LocalCacheFile(newFileOffset, newFilePath));
            }
        }
        finally {
            writeLock.unlock();
        }

        // no lock is needed for the following operation
        if (updated) {
            // remove the the previous or following file as well
            if (previousCacheFile != null) {
                cacheFilesToDelete.add(previousCacheFile.getPath());
            }
            if (followingCacheFile != null) {
                cacheFilesToDelete.add(followingCacheFile.getPath());
            }
        }
        else {
            cacheFilesToDelete = ImmutableSet.of(newFilePath);
        }

        cacheFilesToDelete.forEach(LocalRangeCacheManager::tryDeleteFile);
        return true;
    }

    private static void tryDeleteFile(Path path)
    {
        try {
            File file = new File(path.toUri());
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        }
        catch (IOException e) {
            // ignore
        }
    }

    private static boolean cacheFileEquals(LocalCacheFile left, LocalCacheFile right)
    {
        if (left == null && right == null) {
            return true;
        }

        if (left == null || right == null) {
            return false;
        }

        return left.equals(right);
    }

    private static class LocalCacheFile
    {
        private final long offset;  // the original file offset
        private final Path path;    // the cache location on disk

        public LocalCacheFile(long offset, Path path)
        {
            this.offset = offset;
            this.path = path;
        }

        public long getOffset()
        {
            return offset;
        }

        public Path getPath()
        {
            return path;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            LocalCacheFile that = (LocalCacheFile) o;
            return Objects.equals(offset, that.offset) && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(offset, path);
        }
    }

    private static class CacheRange
    {
        private final RangeMap<Long, LocalCacheFile> range = TreeRangeMap.create();
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        public RangeMap<Long, LocalCacheFile> getRange()
        {
            return range;
        }

        public ReadWriteLock getLock()
        {
            return lock;
        }
    }

    private class CacheRemovalListener
            implements RemovalListener<Path, Boolean>
    {
        @Override
        public void onRemoval(RemovalNotification<Path, Boolean> notification)
        {
            Path path = notification.getKey();
            CacheRange cacheRange = persistedRanges.remove(path);
            if (cacheRange == null) {
                return;
            }

            cacheRemovalExecutor.submit(() -> {
                Collection<LocalCacheFile> files;
                cacheRange.lock.readLock().lock();
                try {
                    files = cacheRange.getRange().asMapOfRanges().values();
                }
                finally {
                    cacheRange.lock.readLock().unlock();
                }

                // There is a chance of the files to be deleted are being read.
                // We may just fail the cache hit and do it in a simple way given the chance is low.
                for (LocalCacheFile file : files) {
                    try {
                        Files.delete(new File(file.getPath().toUri()).toPath());
                    }
                    catch (IOException e) {
                        // ignore
                    }
                }
            });
        }
    }
}
