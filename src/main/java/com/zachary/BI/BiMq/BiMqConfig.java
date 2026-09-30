package com.zachary.BI.BiMq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BiMqConfig {

    @Bean
    public DirectExchange biExchange() {
        // name,
        // durable: exchange persists when mq restarts
        // do not auto delete when there is no consumer and binding
        return new DirectExchange(BiMqConstant.BI_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue biQueue() {
        // name,
        // durable: exchange persists when mq restarts
        return new Queue(BiMqConstant.BI_QUEUE_NAME, true);
    }

    @Bean
    public Binding biBinding(DirectExchange biExchange, Queue biQueue) {
        return BindingBuilder.bind(biQueue)
                .to(biExchange)
                .with(BiMqConstant.BI_ROUTING_KEY);
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(BiMqConstant.RETRY_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue retryQueue() {
        // when message died in this queue, send it to BI_EXCHANGE_NAME, or actual dead letter queue
        return QueueBuilder.durable(BiMqConstant.RETRY_QUEUE_NAME)
                .deadLetterExchange(BiMqConstant.BI_EXCHANGE_NAME)
                .deadLetterRoutingKey(BiMqConstant.BI_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding retryBinding(DirectExchange retryExchange, Queue retryQueue) {
        return BindingBuilder.bind(retryQueue).to(retryExchange).with(BiMqConstant.RETRY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(BiMqConstant.DEAD_LETTER_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return new Queue(BiMqConstant.DEAD_LETTER_QUEUE_NAME, true);
    }

    @Bean
    public Binding deadLetterBinding(DirectExchange deadLetterExchange, Queue deadLetterQueue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with(BiMqConstant.DEAD_LETTER_ROUTING_KEY);
    }
}
