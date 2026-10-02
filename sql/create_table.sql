
-- Create the database
create database if not exists bi_db;

-- Select the database
use bi_db;

-- User table
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment 'Account',
    userPassword varchar(512)                           not null comment 'Password',

    userName     varchar(256)                           null comment 'Display name',
    userAvatar   varchar(1024)                          null comment 'Avatar URL',
    userRole     varchar(256) default 'user'            not null comment 'Role: user/admin/ban',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment 'Created at',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment 'Updated at',
    isDelete     tinyint      default 0                 not null comment 'Deleted flag',
    index idx_userAccount (userAccount)
) comment 'User' collate = utf8mb4_unicode_ci;


create table if not exists chart
(
    id           bigint auto_increment comment 'id' primary key,
    userId           bigint  null comment 'userid',
    name         varchar(128) null comment 'Chart name',
    goal         text null comment 'Analysis goal',
    chartData    text null comment 'Chart data',
    sourceFileName varchar(512) null comment 'Original source filename',
    sourceFileType varchar(16) null comment 'Original source type: csv/xlsx',
    sourceFileSize bigint null comment 'Original source size in bytes',
    chartType    varchar(128) null comment 'Chart type',
    genChart     text null comment 'Generated chart',
    genResult    text null comment 'Analysis conclusion',
    status       varchar(32) default 'wait' not null comment 'Task status',
    execMessage  text null comment 'Task execution message',
    generatedAt  datetime null comment 'Successful AI generation time',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment 'Created at',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment 'Updated at',
    isDelete     tinyint      default 0                 not null comment 'Deleted flag',
    key idx_chart_user_status (userId, status),
    key idx_chart_user_type (userId, chartType),
    key idx_chart_user_generated_at (userId, generatedAt),
    key idx_chart_user_source_size (userId, sourceFileSize)
) comment 'Chart' collate = utf8mb4_unicode_ci;

create table if not exists analysis_job
(
    id                bigint primary key comment 'Job id',
    chartId           bigint       not null comment 'Chart id',
    userId            bigint       not null comment 'Owner id',
    requestId         varchar(64)  not null comment 'Client-independent request id',
    dataFingerprint   char(64)     not null comment 'Input fingerprint',
    activeFingerprint char(64)     null comment 'Input fingerprint while active',
    status            varchar(32)  not null comment 'queued/running/retrying/succeeded/failed/cancelled',
    retryCount        int          not null default 0 comment 'Retries already scheduled',
    maxRetries        int          not null default 3 comment 'Retry limit',
    failureReason     varchar(1000) null comment 'Most recent failure',
    startedAt         datetime     null comment 'First worker start',
    finishedAt        datetime     null comment 'Terminal completion',
    cancelledAt       datetime     null comment 'Cancellation time',
    createTime        datetime     not null default CURRENT_TIMESTAMP,
    updateTime        datetime     not null default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP,
    unique key uk_analysis_job_request (requestId),
    unique key uk_analysis_job_active_input (userId, activeFingerprint),
    key idx_analysis_job_chart (chartId),
    key idx_analysis_job_owner_status (userId, status),
    key idx_analysis_job_status_update (status, updateTime)
) comment 'Reliable analysis job';

create table if not exists analysis_job_event
(
    id          bigint primary key comment 'Event id',
    jobId       bigint       not null comment 'Analysis job id',
    status      varchar(32)  not null comment 'Status at event time',
    message     varchar(512) not null comment 'Human-readable event',
    createTime  datetime     not null default CURRENT_TIMESTAMP,
    key idx_analysis_job_event_job (jobId, createTime)
) comment 'Analysis job timeline event';
