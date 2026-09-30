-- Run this once for databases created before Reliable Analysis Job Center was added.
use bi_db;

create table if not exists analysis_job
(
    id                bigint primary key,
    chartId           bigint       not null,
    userId            bigint       not null,
    requestId         varchar(64)  not null,
    dataFingerprint   char(64)     not null,
    activeFingerprint char(64)     null,
    status            varchar(32)  not null,
    retryCount        int          not null default 0,
    maxRetries        int          not null default 3,
    failureReason     varchar(1000) null,
    startedAt         datetime     null,
    finishedAt        datetime     null,
    cancelledAt       datetime     null,
    createTime        datetime     not null default CURRENT_TIMESTAMP,
    updateTime        datetime     not null default CURRENT_TIMESTAMP on update CURRENT_TIMESTAMP,
    unique key uk_analysis_job_request (requestId),
    unique key uk_analysis_job_active_input (userId, activeFingerprint),
    key idx_analysis_job_chart (chartId),
    key idx_analysis_job_owner_status (userId, status)
);

create table if not exists analysis_job_event
(
    id          bigint primary key,
    jobId       bigint       not null,
    status      varchar(32)  not null,
    message     varchar(512) not null,
    createTime  datetime     not null default CURRENT_TIMESTAMP,
    key idx_analysis_job_event_job (jobId, createTime)
);
