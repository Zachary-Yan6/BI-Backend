package com.zachary.BI.BiMq;


import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.Resource;

@Component
@Slf4j
public class BiMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    public void sendMessage(long jobId) {
        String message = String.valueOf(jobId);
        log.info("Sending analysis job message: {}", message);
        rabbitTemplate.convertAndSend(BiMqConstant.BI_EXCHANGE_NAME, BiMqConstant.BI_ROUTING_KEY, message);
    }

    public void scheduleRetry(long jobId, long delayMillis) {
        rabbitTemplate.convertAndSend(BiMqConstant.RETRY_EXCHANGE_NAME, BiMqConstant.RETRY_ROUTING_KEY,
                String.valueOf(jobId), message -> {
                    message.getMessageProperties().setExpiration(String.valueOf(delayMillis));
                    return message;
                });
    }

    public void sendToDeadLetterQueue(long jobId) {
        rabbitTemplate.convertAndSend(BiMqConstant.DEAD_LETTER_EXCHANGE_NAME,
                BiMqConstant.DEAD_LETTER_ROUTING_KEY, String.valueOf(jobId));
    }
}
