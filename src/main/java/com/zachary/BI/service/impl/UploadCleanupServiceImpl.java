package com.zachary.BI.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zachary.BI.config.UploadProperties;
import com.zachary.BI.mapper.UploadChunkMapper;
import com.zachary.BI.mapper.UploadSessionMapper;
import com.zachary.BI.model.entity.UploadChunk;
import com.zachary.BI.model.entity.UploadSession;
import com.zachary.BI.model.enums.UploadSessionStatusEnum;
import com.zachary.BI.service.UploadCleanupService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class UploadCleanupServiceImpl implements UploadCleanupService {

    /**
     * A directory younger than this is never deleted, even without a session row: createSession creates the
     * directory just before inserting the row, and a chunk may still be being written into an expiring session.
     */
    static final Duration MIN_DIRECTORY_AGE = Duration.ofHours(1);

    /** storageKey is always a server-generated UUID; anything else in the staging root is not ours to delete. */
    private static final Pattern STORAGE_KEY_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final Set<String> RELEASABLE_STATUSES = Set.of(
            UploadSessionStatusEnum.EXPIRED.getValue(),
            UploadSessionStatusEnum.ABORTED.getValue());

    /** Keeps each storageKey IN (...) query to a reasonable size. */
    private static final int LOOKUP_BATCH_SIZE = 500;

    @Resource
    private UploadSessionMapper uploadSessionMapper;
    @Resource
    private UploadChunkMapper uploadChunkMapper;
    @Resource
    private UploadProperties uploadProperties;

    @Override
    public int releaseStaleCompletions() {
        return uploadSessionMapper.releaseStaleCompletions(
                Duration.ofMinutes(uploadProperties.getCompletionLeaseMinutes()).toSeconds());
    }

    @Override
    public int expireAbandonedSessions() {
        return uploadSessionMapper.expireAbandonedSessions(new Date());
    }

    @Override
    public int expireCompletedUploads() {
        return uploadSessionMapper.expireCompletedUploads(
                Duration.ofHours(uploadProperties.getCompletedRetentionHours()).toSeconds());
    }

    @Override
    public int deleteUnusedStorage() {
        Path stagingRoot = Path.of(uploadProperties.getStagingDirectory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(stagingRoot)) {
            return 0;
        }

        List<Path> candidates;
        // Files.list holds an open directory handle until the stream is closed.
        try (Stream<Path> entries = Files.list(stagingRoot)) {
            candidates = entries
                    .filter(Files::isDirectory)
                    .filter(path -> STORAGE_KEY_PATTERN.matcher(path.getFileName().toString()).matches())
                    .filter(this::isOldEnough)
                    .toList();
        } catch (IOException exception) {
            log.error("Could not list upload staging root {}", stagingRoot, exception);
            return 0;
        }

        int deleted = 0;
        for (int start = 0; start < candidates.size(); start += LOOKUP_BATCH_SIZE) {
            List<Path> batch = candidates.subList(start, Math.min(start + LOOKUP_BATCH_SIZE, candidates.size()));
            Map<String, UploadSession> sessions = findSessionsByStorageKey(batch);

            for (Path directory : batch) {
                UploadSession session = sessions.get(directory.getFileName().toString());
                if (session != null && !RELEASABLE_STATUSES.contains(session.getStatus())) {
                    continue;
                }
                if (deleteRecursively(directory)) {
                    deleted++;
                    if (session != null) {
                        // The rows describe files that no longer exist.
                        uploadChunkMapper.delete(new LambdaQueryWrapper<UploadChunk>()
                                .eq(UploadChunk::getUploadSessionId, session.getId()));
                    }
                }
            }
        }
        return deleted;
    }

    private Map<String, UploadSession> findSessionsByStorageKey(List<Path> directories) {
        List<String> storageKeys = directories.stream().map(path -> path.getFileName().toString()).toList();
        return uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
                        .select(UploadSession::getId, UploadSession::getStorageKey, UploadSession::getStatus)
                        .in(UploadSession::getStorageKey, storageKeys))
                .stream()
                .collect(Collectors.toMap(UploadSession::getStorageKey, Function.identity()));
    }

    private boolean isOldEnough(Path directory) {
        try {
            Instant lastModified = Files.getLastModifiedTime(directory).toInstant();
            return lastModified.isBefore(Instant.now().minus(MIN_DIRECTORY_AGE));
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Deletes children before parents. Returns false if anything could not be deleted; the next run retries.
     */
    private boolean deleteRecursively(Path directory) {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException exception) {
            log.warn("Could not scan upload directory {}", directory, exception);
            return false;
        }

        boolean complete = true;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                complete = false;
                log.warn("Could not delete upload file {}", path, exception);
            }
        }
        return complete;
    }
}
