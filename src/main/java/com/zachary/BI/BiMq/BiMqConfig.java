package com.zachary.BI.BiMq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * One retry queue per delay tier. A message waits in it for its own TTL and then dead-letters back to the
     * analysis queue. Delays are per message, not queue arguments, so changing them never conflicts with a queue
     * that already exists on the broker.
     */
    @Bean
    public Declarables retryQueues(DirectExchange retryExchange, RetryDelayPolicy retryDelayPolicy) {
        List<Declarable> declarables = new ArrayList<>();
        for (int tier = 1; tier <= retryDelayPolicy.maxRetries(); tier++) {
            Queue queue = QueueBuilder.durable(BiMqConstant.RETRY_QUEUE_NAME_PREFIX + tier)
                    .deadLetterExchange(BiMqConstant.BI_EXCHANGE_NAME)
                    .deadLetterRoutingKey(BiMqConstant.BI_ROUTING_KEY)
                    .build();
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(retryExchange).with(BiMqConstant.RETRY_ROUTING_KEY_PREFIX + tier));
        }
        return new Declarables(declarables);
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
