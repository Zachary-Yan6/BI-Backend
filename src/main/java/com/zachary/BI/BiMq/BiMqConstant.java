package com.zachary.BI.BiMq;

public interface BiMqConstant {

    String BI_EXCHANGE_NAME = "bi.analysis.exchange";
    String BI_QUEUE_NAME = "bi.analysis.queue";
    String BI_ROUTING_KEY = "bi.analysis.run";
    String RETRY_EXCHANGE_NAME = "bi.analysis.retry.exchange";
    /**
     * One retry queue per delay tier: "bi.analysis.retry.queue.1", ".2", ... (see RetryDelayPolicy).
     * The earlier single queue "bi.analysis.retry.queue" is no longer used.
     */
    String RETRY_QUEUE_NAME_PREFIX = "bi.analysis.retry.queue.";
    String RETRY_ROUTING_KEY_PREFIX = "bi.analysis.retry.";
    String DEAD_LETTER_EXCHANGE_NAME = "bi.analysis.dead-letter.exchange";
    String DEAD_LETTER_QUEUE_NAME = "bi.analysis.dead-letter.queue";
    String DEAD_LETTER_ROUTING_KEY = "bi.analysis.dead";
}
