package com.zachary.BI.BiMq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BiMessageProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private BiMessageProducer producer;

    @Test
    void sendMessage_whenBrokerAcks_shouldSucceed() {
        brokerResponds(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null)));

        assertDoesNotThrow(() -> producer.sendMessage(7L));
        verify(rabbitTemplate).convertAndSend(eq(BiMqConstant.BI_EXCHANGE_NAME), eq(BiMqConstant.BI_ROUTING_KEY),
                eq((Object) "7"), any(CorrelationData.class));
    }

    @Test
    void sendMessage_whenBrokerNacks_shouldFail() {
        brokerResponds(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "disk full")));

        AmqpException exception = assertThrows(AmqpException.class, () -> producer.sendMessage(7L));
        assertTrue(exception.getMessage().contains("disk full"));
    }

    @Test
    void sendMessage_whenNoQueueIsBound_shouldFailEvenThoughBrokerAcks() {
        brokerResponds(correlation -> {
            // RabbitMQ acks an unroutable mandatory message after returning it.
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE",
                    BiMqConstant.BI_EXCHANGE_NAME, BiMqConstant.BI_ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        AmqpException exception = assertThrows(AmqpException.class, () -> producer.sendMessage(7L));
        assertTrue(exception.getMessage().contains("NO_ROUTE"));
    }

    @Test
    void sendMessage_whenConfirmFails_shouldFail() {
        brokerResponds(correlation -> correlation.getFuture().completeExceptionally(new IllegalStateException("closed")));

        assertThrows(AmqpException.class, () -> producer.sendMessage(7L));
    }

    @Test
    void scheduleRetry_shouldRouteToTierQueueWithTtlAndWaitForConfirm() {
        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        doAnswer(invocation -> {
            invocation.<CorrelationData>getArgument(4).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(BiMqConstant.RETRY_EXCHANGE_NAME),
                eq("bi.analysis.retry.2"), eq((Object) "7"), postProcessor.capture(), any(CorrelationData.class));

        producer.scheduleRetry(7L, 2, 4_000L);

        Message message = postProcessor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertEquals("4000", message.getMessageProperties().getExpiration());
    }

    @Test
    void sendToDeadLetterQueue_shouldWaitForConfirm() {
        brokerResponds(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "nack")));

        assertThrows(AmqpException.class, () -> producer.sendToDeadLetterQueue(7L));
    }

    /** Stubs the four-argument convertAndSend used for messages without a post-processor. */
    private void brokerResponds(Consumer<CorrelationData> response) {
        doAnswer(invocation -> {
            response.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(CorrelationData.class));
    }
}
