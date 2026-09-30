package com.zachary.BI.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.config.UploadProperties;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.exception.ThrowUtils;
import com.zachary.BI.mapper.UploadChunkMapper;
import com.zachary.BI.mapper.UploadSessionMapper;
import com.zachary.BI.model.dto.upload.CompletedUploadFile;
import com.zachary.BI.model.dto.upload.CreateUploadSessionRequest;
import com.zachary.BI.model.entity.UploadChunk;
import com.zachary.BI.model.entity.UploadSession;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.enums.UploadSessionStatusEnum;
import com.zachary.BI.model.vo.*;
import com.zachary.BI.service.ResumableUploadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ResumableUploadServiceImpl implements ResumableUploadService {

    private static final List<String> ALLOWED_FILE_TYPES = List.of("csv", "xlsx");
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");

    @Resource
    private UploadProperties uploadProperties;

    @Resource
    private UploadSessionMapper uploadSessionMapper;

    @Resource
    private UploadChunkMapper uploadChunkMapper;

    @Override
    public CreateUploadSessionResponse createSession(
            CreateUploadSessionRequest request,
            User user
    ) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        String originalFileName = StringUtils.trimToNull(request.getFileName());

        ThrowUtils.throwIf(
                originalFileName == null || originalFileName.length() > 512,
                ErrorCode.PARAMS_ERROR,
                "A valid file name is required."
        );

        ThrowUtils.throwIf(
                request.getTotalSize() == null || request.getTotalSize() <= 0,
                ErrorCode.PARAMS_ERROR,
                "File size must be greater than zero."
        );

        ThrowUtils.throwIf(
                request.getTotalSize() > uploadProperties.getMaxFileSizeBytes(),
                ErrorCode.PARAMS_ERROR,
                "File exceeds the resumable-upload size limit."
        );

        String sourceFileType = FileUtil.getSuffix(originalFileName).toLowerCase();

        ThrowUtils.throwIf(
                !ALLOWED_FILE_TYPES.contains(sourceFileType),
                ErrorCode.PARAMS_ERROR,
                "Only CSV and XLSX files are supported."
        );

        int chunkSize = uploadProperties.getChunkSizeBytes();

        // The server determines totalChunks; the client cannot manipulate it.
        long totalChunksLong = (
                request.getTotalSize() + chunkSize - 1
        ) / chunkSize;

        ThrowUtils.throwIf(
                totalChunksLong > Integer.MAX_VALUE,
                ErrorCode.PARAMS_ERROR,
                "Too many file chunks."
        );

        String uploadId = UUID.randomUUID().toString();
        String storageKey = UUID.randomUUID().toString();

        Path stagingRoot = Path.of(uploadProperties.getStagingDirectory())
                .toAbsolutePath()
                .normalize();

        Path sessionDirectory = stagingRoot.resolve(storageKey).normalize();

        // Prevent path traversal even if future code changes storageKey handling.
        ThrowUtils.throwIf(
                !sessionDirectory.startsWith(stagingRoot),
                ErrorCode.SYSTEM_ERROR,
                "Invalid upload storage location."
        );

        try {
            Files.createDirectories(sessionDirectory);
        } catch (IOException exception) {
            log.error("Could not create upload staging directory", exception);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Unable to initialise upload storage."
            );
        }

        Date expiresAt = Date.from(
                Instant.now()
                        .plus(uploadProperties.getSessionTtlHours(), ChronoUnit.HOURS)
        );

        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setUserId(user.getId());
        session.setOriginalFileName(originalFileName);
        session.setSourceFileType(sourceFileType);
        session.setContentType(
                StringUtils.abbreviate(
                        StringUtils.defaultString(request.getContentType()),
                        128
                )
        );
        session.setTotalSize(request.getTotalSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks((int) totalChunksLong);
        session.setStorageKey(storageKey);
        session.setStatus(UploadSessionStatusEnum.CREATED.getValue());
        session.setExpiresAt(expiresAt);

        try {
            uploadSessionMapper.insert(session);
        } catch (RuntimeException exception) {
            // Avoid leaving an empty staging directory when database creation fails.
            try {
                Files.deleteIfExists(sessionDirectory);
            } catch (IOException cleanupException) {
                log.warn("Could not clean up staging directory {}", sessionDirectory);
            }
            throw exception;
        }

        CreateUploadSessionResponse response =
                new CreateUploadSessionResponse();

        response.setUploadId(uploadId);
        response.setChunkSize(chunkSize);
        response.setTotalChunks((int) totalChunksLong);
        response.setUploadedChunkIndexes(List.of());
        response.setExpiresAt(expiresAt);

        return response;
    }

    @Override
    public UploadSessionStatusResponse getSessionStatus(
            String uploadId,
            User user
    ) {
        UploadSession session = uploadSessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>()
                        .eq(UploadSession::getUploadId, uploadId)
                        .eq(UploadSession::getUserId, user.getId())
                        .last("LIMIT 1")
        );

        // Returning NOT_FOUND prevents users from learning whether another user's
        // uploadId exists.
        ThrowUtils.throwIf(
                session == null,
                ErrorCode.NOT_FOUND_ERROR,
                "Upload session was not found."
        );

        if (session.getExpiresAt().before(new Date())
                && UploadSessionStatusEnum.isActive(session.getStatus())) {

            // Expired uploads cannot receive more chunks.
            session.setStatus(UploadSessionStatusEnum.EXPIRED.getValue());
            uploadSessionMapper.updateById(session);
        }

        List<Integer> uploadedIndexes = uploadChunkMapper.selectList(
                new LambdaQueryWrapper<UploadChunk>()
                        .eq(UploadChunk::getUploadSessionId, session.getId())
                        .orderByAsc(UploadChunk::getChunkIndex)
        ).stream().map(UploadChunk::getChunkIndex).toList();

        UploadSessionStatusResponse response =
                new UploadSessionStatusResponse();

        response.setUploadId(session.getUploadId());
        response.setStatus(session.getStatus());
        response.setChunkSize(session.getChunkSize());
        response.setTotalChunks(session.getTotalChunks());
        response.setUploadedChunkIndexes(uploadedIndexes);
        response.setExpiresAt(session.getExpiresAt());

        // A completed session may also have all chunks, so require active state.
        response.setReadyToComplete(
                UploadSessionStatusEnum.isActive(session.getStatus())
                        && uploadedIndexes.size() == session.getTotalChunks()
        );

        return response;
    }

    @Override
    public UploadChunkResponse uploadChunk(
            String uploadId,
            int chunkIndex,
            String contentRangeHeader,
            String chunkSha256,
            long contentLength,
            InputStream inputStream,
            User user
    ) throws IOException {
        UploadSession session = uploadSessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>()
                        .eq(UploadSession::getUploadId, uploadId)
                        .eq(UploadSession::getUserId, user.getId())
                        .last("LIMIT 1")
        );

        // Do not reveal whether another user's uploadId exists.
        ThrowUtils.throwIf(
                session == null,
                ErrorCode.NOT_FOUND_ERROR,
                "Upload session was not found."
        );

        ThrowUtils.throwIf(
                !UploadSessionStatusEnum.isActive(session.getStatus()),
                ErrorCode.OPERATION_ERROR,
                "This upload session is no longer active."
        );

        ThrowUtils.throwIf(
                session.getExpiresAt().before(new Date()),
                ErrorCode.OPERATION_ERROR,
                "This upload session has expired."
        );

        ThrowUtils.throwIf(
                chunkIndex < 0 || chunkIndex >= session.getTotalChunks(),
                ErrorCode.PARAMS_ERROR,
                "Chunk index is outside the allowed range."
        );

        ThrowUtils.throwIf(
                chunkSha256 == null || !chunkSha256.matches("(?i)^[a-f0-9]{64}$"),
                ErrorCode.PARAMS_ERROR,
                "X-Chunk-SHA256 must be a valid SHA-256 value."
        );

        ContentRange contentRange = parseContentRange(contentRangeHeader);

        long expectedStart = (long) chunkIndex * session.getChunkSize();
        long expectedLength = Math.min(
                session.getChunkSize(),
                session.getTotalSize() - expectedStart
        );
        long expectedEnd = expectedStart + expectedLength - 1;

        ThrowUtils.throwIf(
                contentRange.total() != session.getTotalSize()
                        || contentRange.start() != expectedStart
                        || contentRange.end() != expectedEnd,
                ErrorCode.PARAMS_ERROR,
                "Content-Range does not match the expected chunk range."
        );

        // Content-Length is required so the server can reject malformed requests early.
        ThrowUtils.throwIf(
                contentLength != expectedLength,
                ErrorCode.PARAMS_ERROR,
                "Chunk byte length does not match the expected size."
        );

        UploadChunk existingChunk = uploadChunkMapper.selectOne(
                new LambdaQueryWrapper<UploadChunk>()
                        .eq(UploadChunk::getUploadSessionId, session.getId())
                        .eq(UploadChunk::getChunkIndex, chunkIndex)
                        .last("LIMIT 1")
        );

        // Retrying the exact same chunk is safe and does not write a second file.
        if (existingChunk != null) {
            ThrowUtils.throwIf(
                    !existingChunk.getSha256().equalsIgnoreCase(chunkSha256)
                            || existingChunk.getChunkSize() != expectedLength,
                    ErrorCode.OPERATION_ERROR,
                    "A different chunk already exists at this index."
            );

            return buildChunkResponse(session, chunkIndex, true);
        }

        Path stagingRoot = Path.of(uploadProperties.getStagingDirectory())
                .toAbsolutePath()
                .normalize();

        Path sessionDirectory = stagingRoot
                .resolve(session.getStorageKey())
                .normalize();

        ThrowUtils.throwIf(
                !sessionDirectory.startsWith(stagingRoot),
                ErrorCode.SYSTEM_ERROR,
                "Invalid upload storage location."
        );

        Files.createDirectories(sessionDirectory);

        // All filenames are generated by the server.
        Path temporaryFile = sessionDirectory.resolve(
                ".chunk-" + chunkIndex + "-" + UUID.randomUUID() + ".tmp"
        );

        String actualSha256;
        long writtenBytes;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (
                    InputStream source = inputStream;
                    OutputStream target = Files.newOutputStream(temporaryFile)
            ) {
                byte[] buffer = new byte[8192];
                writtenBytes = 0;

                int read;
                while ((read = source.read(buffer)) != -1) {
                    writtenBytes += read;

                    // Reject oversized bodies even if Content-Length was spoofed.
                    ThrowUtils.throwIf(
                            writtenBytes > expectedLength,
                            ErrorCode.PARAMS_ERROR,
                            "Chunk body exceeds the expected size."
                    );

                    digest.update(buffer, 0, read);
                    target.write(buffer, 0, read);
                }
            }

            actualSha256 = HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException exception) {
            Files.deleteIfExists(temporaryFile);
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "SHA-256 is unavailable."
            );
        } catch (Exception exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        }

        ThrowUtils.throwIf(
                writtenBytes != expectedLength,
                ErrorCode.PARAMS_ERROR,
                "Chunk body is shorter than the expected size."
        );

        ThrowUtils.throwIf(
                !actualSha256.equalsIgnoreCase(chunkSha256),
                ErrorCode.PARAMS_ERROR,
                "Chunk SHA-256 verification failed."
        );

        // Hash is part of the final filename. Two conflicting concurrent writes
        // can never overwrite each other.
        Path finalChunkFile = sessionDirectory.resolve(
                "chunk-%08d-%s.part".formatted(chunkIndex, actualSha256)
        );

        boolean finalFileCreated = false;

        try {
            try {
                Files.move(
                        temporaryFile,
                        finalChunkFile,
                        StandardCopyOption.ATOMIC_MOVE
                );
                finalFileCreated = true;
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, finalChunkFile);
                finalFileCreated = true;
            }

            UploadChunk chunk = new UploadChunk();
            chunk.setUploadSessionId(session.getId());
            chunk.setChunkIndex(chunkIndex);
            chunk.setChunkSize((int) writtenBytes);
            chunk.setSha256(actualSha256);

            uploadChunkMapper.insert(chunk);

        } catch (DuplicateKeyException exception) {
            // A concurrent request may have completed first.
            UploadChunk winningChunk = uploadChunkMapper.selectOne(
                    new LambdaQueryWrapper<UploadChunk>()
                            .eq(UploadChunk::getUploadSessionId, session.getId())
                            .eq(UploadChunk::getChunkIndex, chunkIndex)
                            .last("LIMIT 1")
            );

            if (finalFileCreated) {
                Files.deleteIfExists(finalChunkFile);
            }

            ThrowUtils.throwIf(
                    winningChunk == null
                            || !winningChunk.getSha256().equalsIgnoreCase(actualSha256),
                    ErrorCode.OPERATION_ERROR,
                    "A conflicting chunk was uploaded concurrently."
            );

            return buildChunkResponse(session, chunkIndex, true);

        } catch (Exception exception) {
            if (finalFileCreated) {
                Files.deleteIfExists(finalChunkFile);
            } else {
                Files.deleteIfExists(temporaryFile);
            }
            throw exception;
        }

        // Update the status only after a validated chunk record was created.
        if (UploadSessionStatusEnum.CREATED.getValue().equals(session.getStatus())) {
            session.setStatus(UploadSessionStatusEnum.UPLOADING.getValue());
            uploadSessionMapper.updateById(session);
        }

        return buildChunkResponse(session, chunkIndex, false);
    }

    @Override
    public UploadCompletionResponse completeUpload(String uploadId, User loginUser) throws IOException {
        Long userId = loginUser.getId();
        UploadSession session = getOwnedSession(uploadId, userId);

        // Repeated requests are safe: return the original completion result.
        if (UploadSessionStatusEnum.COMPLETED.getValue().equals(session.getStatus())) {
            return buildCompletionResponse(session);
        }

        if (!UploadSessionStatusEnum.acceptsChunks(session.getStatus())) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "This upload cannot be completed in its current state"
            );
        }

        String claimId = UUID.randomUUID().toString();

        // Only one request can change uploading/created to completing.
        int claimed = uploadSessionMapper.claimCompletion(
                session.getId(),
                userId,
                claimId,
                UploadSessionStatusEnum.COMPLETING.getValue(),
                UploadSessionStatusEnum.CREATED.getValue(),
                UploadSessionStatusEnum.UPLOADING.getValue()
        );

        if (claimed != 1) {
            UploadSession latestSession = getOwnedSession(uploadId, userId);

            if (UploadSessionStatusEnum.COMPLETED.getValue().equals(latestSession.getStatus())) {
                return buildCompletionResponse(latestSession);
            }

            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Another completion request is already in progress"
            );
        }

        try {
            List<UploadChunk> chunks = loadAndValidateChunks(session);

            // This streams data to disk. It never loads the whole file into memory.
            MergedUpload mergedUpload = mergeChunks(session, chunks);

            int updated = uploadSessionMapper.markCompleted(
                    session.getId(),
                    userId,
                    claimId,
                    UploadSessionStatusEnum.COMPLETED.getValue(),
                    UploadSessionStatusEnum.COMPLETING.getValue(),
                    mergedUpload.completedStorageKey(),
                    mergedUpload.wholeFileSha256()
            );

            if (updated != 1) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Upload completion lease was lost; please retry"
                );
            }

            session.setCompletedStorageKey(mergedUpload.completedStorageKey());
            session.setWholeFileSha256(mergedUpload.wholeFileSha256());
            session.setCompletedAt(new Date());

            // A cleanup failure must not make a successful upload appear failed.
            deleteChunkFilesQuietly(session, chunks);

            return buildCompletionResponse(session);
        } catch (IOException | RuntimeException exception) {
            // Keep chunks so the user can retry completion without re-uploading them.
            uploadSessionMapper.releaseCompletion(
                    session.getId(),
                    userId,
                    claimId,
                    UploadSessionStatusEnum.UPLOADING.getValue(),
                    UploadSessionStatusEnum.COMPLETING.getValue()
            );
            throw exception;
        }
    }

    private MergedUpload mergeChunks(
            UploadSession session,
            List<UploadChunk> chunks
    ) throws IOException {
        Path uploadDirectory = sessionDirectory(session);

        Path temporaryFile = uploadDirectory.resolve(
                "completed-" + UUID.randomUUID() + ".tmp"
        );


        long totalWritten = 0;
        boolean merged = false;

        try {
            MessageDigest wholeFileDigest = sha256Digest();

            try (OutputStream outputStream = Files.newOutputStream(
                    temporaryFile,
                    // create this file when this file do not exist, otherwise throws error
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                byte[] buffer = new byte[8192];

                for (UploadChunk chunk : chunks) {
                    Path partFile = chunkPath(
                            session,
                            chunk.getChunkIndex(),
                            chunk.getSha256()
                    );

                    MessageDigest chunkDigest = sha256Digest();
                    long chunkWritten = 0;

                    try (InputStream inputStream = Files.newInputStream(partFile)) {
                        int bytesRead;

                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            wholeFileDigest.update(buffer, 0, bytesRead);
                            chunkDigest.update(buffer, 0, bytesRead);

                            chunkWritten += bytesRead;
                            totalWritten += bytesRead;
                        }
                    }

                    String actualChunkHash = HexFormat.of()
                            .formatHex(chunkDigest.digest());

                    if (chunkWritten != chunk.getChunkSize()
                            || !actualChunkHash.equalsIgnoreCase(chunk.getSha256())) {
                        throw new BusinessException(
                                ErrorCode.OPERATION_ERROR,
                                "Chunk integrity verification failed"
                        );
                    }
                }
            }

            if (totalWritten != session.getTotalSize()) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Merged file size does not match the upload session"
                );
            }

            String wholeFileSha256 = HexFormat.of()
                    .formatHex(wholeFileDigest.digest());

            String finalFileName = "completed-" + wholeFileSha256 + ".data";
            Path finalFile = uploadDirectory.resolve(finalFileName);
            moveWithoutOverwriting(temporaryFile, finalFile);

            String completedStorageKey =
                    session.getStorageKey() + "/" + finalFileName;

            merged = true;
            return new MergedUpload(completedStorageKey, wholeFileSha256);
        } finally {
            if (!merged) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private void moveWithoutOverwriting(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ignored) {
            // An earlier identical completion already created this hash-addressed file.
            Files.deleteIfExists(source);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(source, target);
            } catch (FileAlreadyExistsException ignored) {
                Files.deleteIfExists(source);
            }
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record MergedUpload(
            String completedStorageKey,
            String wholeFileSha256
    ) {
    }

    private List<UploadChunk> loadAndValidateChunks(UploadSession session) throws IOException {
        List<UploadChunk> chunks = uploadChunkMapper.selectList(
                new LambdaQueryWrapper<UploadChunk>()
                        .eq(UploadChunk::getUploadSessionId, session.getId())
                        .orderByAsc(UploadChunk::getChunkIndex)
        );

        if (chunks.size() != session.getTotalChunks()) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Not all chunks have been uploaded"
            );
        }

        for (int index = 0; index < session.getTotalChunks(); index++) {
            UploadChunk chunk = chunks.get(index);

            if (!Objects.equals(chunk.getChunkIndex(), index)) {
                throw new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "Chunk sequence is incomplete"
                );
            }

            long expectedSize = expectedChunkSize(session, index);

            if (!Objects.equals(chunk.getChunkSize(), (int) expectedSize)) {
                throw new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "Chunk size metadata is invalid"
                );
            }

            Path partFilePath = chunkPath(
                    session,
                    chunk.getChunkIndex(),
                    chunk.getSha256()
            );

            if (!Files.isRegularFile(partFilePath)) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "A chunk file is missing from storage"
                );
            }

            try {
                if (Files.size(partFilePath) != expectedSize) {
                    throw new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "A chunk file has an invalid size"
                    );
                }
            } catch (IOException exception) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "Unable to verify the uploaded chunk"
                );
            }

        }

        return chunks;
    }

    @Override
    public CompletedUploadFile resolveCompletedUpload(
            String fileToken,
            User user
    ) throws IOException {
        String uploadId = StringUtils.trimToNull(fileToken);

        ThrowUtils.throwIf(
                uploadId == null,
                ErrorCode.PARAMS_ERROR,
                "A completed upload token is required."
        );

        // getOwnedSession checks both uploadId and authenticated user ID.
        UploadSession session = getOwnedSession(uploadId, user.getId());

        ThrowUtils.throwIf(
                !UploadSessionStatusEnum.COMPLETED.getValue().equals(session.getStatus()),
                ErrorCode.OPERATION_ERROR,
                "The uploaded file is not ready for analysis."
        );

        ThrowUtils.throwIf(
                StringUtils.isBlank(session.getCompletedStorageKey())
                        || StringUtils.isBlank(session.getWholeFileSha256()),
                ErrorCode.SYSTEM_ERROR,
                "Completed upload metadata is invalid."
        );

        Path stagingRoot = Path.of(uploadProperties.getStagingDirectory())
                .toAbsolutePath()
                .normalize();

        Path completedFile = stagingRoot
                .resolve(session.getCompletedStorageKey())
                .normalize();

        // The stored path must always remain inside the private staging root.
        ThrowUtils.throwIf(
                !completedFile.startsWith(stagingRoot),
                ErrorCode.SYSTEM_ERROR,
                "Invalid completed upload storage location."
        );

        ThrowUtils.throwIf(
                !Files.isRegularFile(completedFile),
                ErrorCode.OPERATION_ERROR,
                "The completed upload file is no longer available."
        );

        ThrowUtils.throwIf(
                Files.size(completedFile) != session.getTotalSize(),
                ErrorCode.OPERATION_ERROR,
                "Completed upload file size does not match its metadata."
        );

        return new CompletedUploadFile(
                completedFile,
                session.getOriginalFileName(),
                session.getSourceFileType(),
                session.getTotalSize(),
                session.getWholeFileSha256()
        );
    }

    /**
     * Finds an upload session owned by the current user.
     * A missing session and another user's session both return the same error,
     * preventing upload ID enumeration.
     */
    private UploadSession getOwnedSession(String uploadId, Long userId) {
        UploadSession session = uploadSessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>()
                        .eq(UploadSession::getUploadId, uploadId)
                        .eq(UploadSession::getUserId, userId)
                        .last("LIMIT 1")
        );

        ThrowUtils.throwIf(
                session == null,
                ErrorCode.NOT_FOUND_ERROR,
                "Upload session was not found."
        );

        return session;
    }

    /**
     * Returns only safe metadata to the client.
     * The physical completedStorageKey must remain private to the server.
     */
    private UploadCompletionResponse buildCompletionResponse(
            UploadSession session
    ) {
        UploadCompletionResponse response = new UploadCompletionResponse();

        // uploadId becomes a file token only when resolved with the current user ID.
        response.setFileToken(session.getUploadId());
        response.setOriginalFileName(session.getOriginalFileName());
        response.setSourceFileType(session.getSourceFileType());
        response.setTotalSize(session.getTotalSize());
        response.setWholeFileSha256(session.getWholeFileSha256());
        response.setCompletedAt(session.getCompletedAt());

        return response;
    }

    /**
     * Creates and returns the server-generated folder for one upload session.
     */
    private Path sessionDirectory(UploadSession session) throws IOException {
        Path stagingRoot = Path.of(uploadProperties.getStagingDirectory())
                .toAbsolutePath()
                .normalize();

        Path uploadDirectory = stagingRoot
                .resolve(session.getStorageKey())
                .normalize();

        ThrowUtils.throwIf(
                !uploadDirectory.startsWith(stagingRoot),
                ErrorCode.SYSTEM_ERROR,
                "Invalid upload storage location."
        );

        Files.createDirectories(uploadDirectory);
        return uploadDirectory;
    }

    /**
     * Derives the deterministic private path of one validated chunk.
     */
    private Path chunkPath(
            UploadSession session,
            int chunkIndex,
            String sha256
    ) throws IOException {
        ThrowUtils.throwIf(
                chunkIndex < 0 || chunkIndex >= session.getTotalChunks(),
                ErrorCode.PARAMS_ERROR,
                "Chunk index is outside the allowed range."
        );

        ThrowUtils.throwIf(
                sha256 == null || !sha256.matches("(?i)^[a-f0-9]{64}$"),
                ErrorCode.PARAMS_ERROR,
                "Invalid stored chunk checksum."
        );

        Path uploadDirectory = sessionDirectory(session);

        Path partFilePath = uploadDirectory.resolve(
                "chunk-%08d-%s.part".formatted(chunkIndex, sha256)
        ).normalize();

        ThrowUtils.throwIf(
                !partFilePath.startsWith(uploadDirectory),
                ErrorCode.SYSTEM_ERROR,
                "Invalid chunk storage location."
        );

        return partFilePath;
    }

    /**
     * Removes chunk files only after the final merged file has been persisted.
     * Cleanup failures are logged but never invalidate a completed upload.
     */
    private void deleteChunkFilesQuietly(
            UploadSession session,
            List<UploadChunk> chunks
    ) {
        for (UploadChunk chunk : chunks) {
            try {
                Files.deleteIfExists(
                        chunkPath(
                                session,
                                chunk.getChunkIndex(),
                                chunk.getSha256()
                        )
                );
            } catch (IOException exception) {
                log.warn(
                        "Could not delete completed upload chunk. uploadId={}, chunkIndex={}",
                        session.getUploadId(),
                        chunk.getChunkIndex(),
                        exception
                );
            }
        }
    }

    private long expectedChunkSize(UploadSession session, int chunkIndex) {
        long startOffset = (long) chunkIndex * session.getChunkSize();
        return Math.min(session.getChunkSize(), session.getTotalSize() - startOffset);
    }

    private ContentRange parseContentRange(String header) {
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(
                StringUtils.defaultString(header)
        );

        ThrowUtils.throwIf(
                !matcher.matches(),
                ErrorCode.PARAMS_ERROR,
                "Content-Range must follow: bytes start-end/total"
        );

        try {
            return new ContentRange(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3))
            );
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Content-Range contains invalid numeric values."
            );
        }
    }

    private UploadChunkResponse buildChunkResponse(
            UploadSession session,
            int chunkIndex,
            boolean alreadyUploaded
    ) {
        long uploadedCount = uploadChunkMapper.selectCount(
                new LambdaQueryWrapper<UploadChunk>()
                        .eq(UploadChunk::getUploadSessionId, session.getId())
        );

        UploadChunkResponse response = new UploadChunkResponse();
        response.setUploadId(session.getUploadId());
        response.setChunkIndex(chunkIndex);
        response.setAlreadyUploaded(alreadyUploaded);
        response.setUploadedChunks((int) uploadedCount);
        response.setTotalChunks(session.getTotalChunks());
        response.setReadyToComplete(uploadedCount == session.getTotalChunks());

        return response;
    }

    private record ContentRange(long start, long end, long total) {
    }
}
