package com.zachary.BI.BiMq;


import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes analysis job ids and only returns once RabbitMQ has confirmed it stored and routed the message.
 * <p>
 * Without this, {@code convertAndSend} returning normally only meant "written to the socket": a message the
 * broker rejected, or one that no queue was bound to receive, was lost without any error. Every failure here is
 * an {@link AmqpException} (a RuntimeException), which the existing callers already handle by failing the job or
 * leaving it for the recovery task.
 */
@Component
@Slf4j
public class BiMessageProducer {

    /** A confirm normally arrives within milliseconds; waiting longer only delays the caller's error handling. */
    static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    @Resource
    private RabbitTemplate rabbitTemplate;

    public void sendMessage(long jobId) {
        log.info("Sending analysis job message: {}", jobId);
        publish(BiMqConstant.BI_EXCHANGE_NAME, BiMqConstant.BI_ROUTING_KEY, jobId, null);
    }

    /**
     * @param tier the retry queue from {@link RetryDelayPolicy.RetryPlan#tier()}; it must hold delays of about this
     *             length, because RabbitMQ only expires the message at the head of a queue
     */
    public void scheduleRetry(long jobId, int tier, long delayMillis) {
        publish(BiMqConstant.RETRY_EXCHANGE_NAME, BiMqConstant.RETRY_ROUTING_KEY_PREFIX + tier, jobId, message -> {
            message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
            return message;
        });
    }

    public void sendToDeadLetterQueue(long jobId) {
        publish(BiMqConstant.DEAD_LETTER_EXCHANGE_NAME, BiMqConstant.DEAD_LETTER_ROUTING_KEY, jobId, null);
    }

    private void publish(String exchange, String routingKey, long jobId, MessagePostProcessor postProcessor) {
        CorrelationData correlation = new CorrelationData(jobId + ":" + UUID.randomUUID());
        String payload = String.valueOf(jobId);
        if (postProcessor == null) {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, correlation);
        } else {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, postProcessor, correlation);
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for the publisher confirm of job " + jobId, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("RabbitMQ did not confirm the message for job " + jobId + " within "
                    + CONFIRM_TIMEOUT.toSeconds() + "s", exception);
        }
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ rejected the message for job " + jobId + ": " + confirm.getReason());
        }
        // With mandatory publishing the broker still acks an unroutable message, but hands it back first.
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new AmqpException("No queue is bound for the message of job " + jobId + " (exchange " + exchange
                    + ", routing key " + routingKey + "): " + returned.getReplyText());
        }
    }
}
