use bi_db;

-- One row represents one private, resumable upload session.
create table if not exists upload_session
(
    id               bigint primary key comment 'Internal upload session id',

    -- Public UUID returned to the frontend. Never expose the internal id.
    uploadId         varchar(64)  not null comment 'Public upload session UUID',

    -- Every chunk request must match this owner.
    userId           bigint       not null comment 'Upload owner id',

    -- Metadata only. Never use originalFileName as a filesystem path.
    originalFileName varchar(512) not null comment 'Original user filename',
    sourceFileType   varchar(16)  not null comment 'Allowed type: csv/xlsx',
    contentType      varchar(128) null comment 'Client reported content type',

    -- Server validates these values before accepting chunks.
    totalSize        bigint       not null comment 'Expected total file size in bytes',
    chunkSize        int          not null comment 'Server-selected chunk size in bytes',
    totalChunks      int          not null comment 'Expected number of chunks',

    -- Opaque server-side directory / object-storage key, never a user-controlled path.
    storageKey       varchar(128) not null comment 'Private staging storage key',

    -- created / uploading / completed / expired / aborted
    status           varchar(32)  not null comment 'Upload lifecycle status',

    expiresAt        datetime     not null comment 'Temporary chunk cleanup deadline',
    completedAt      datetime     null comment 'Successful assembly time',
    createTime       datetime     not null default CURRENT_TIMESTAMP,
    updateTime       datetime     not null default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP,

    unique key uk_upload_session_upload_id (uploadId),
    key idx_upload_session_user_status (userId, status),
    key idx_upload_session_expiry (status, expiresAt)
    ) comment 'Resumable upload session';


-- One row is created only after a chunk has passed validation and reached disk.
create table if not exists upload_chunk
(
    id              bigint primary key comment 'Internal chunk id',
    uploadSessionId bigint       not null comment 'Parent upload session id',

    -- Zero-based index: 0, 1, 2 ...
    chunkIndex      int          not null comment 'Zero-based chunk index',
    chunkSize       int          not null comment 'Actual received bytes',

    -- SHA-256 of this chunk, used for idempotency and corruption detection.
    sha256          char(64)     not null comment 'Chunk SHA-256 checksum',

    createTime      datetime     not null default CURRENT_TIMESTAMP,

    unique key uk_upload_chunk_session_index (uploadSessionId, chunkIndex),
    key idx_upload_chunk_session (uploadSessionId)
    ) comment 'Validated resumable upload chunk';