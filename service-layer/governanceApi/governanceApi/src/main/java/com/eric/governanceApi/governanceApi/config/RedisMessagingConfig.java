package com.eric.governanceApi.governanceApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.eric.governanceApi.governanceApi.service.LiveStateSubscriber;

@Configuration
public class RedisMessagingConfig {
    
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
        RedisConnectionFactory connectionFactory,
        LiveStateSubscriber subscriber) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(subscriber, new PatternTopic(LiveStateSubscriber.CHANNEL));
            return container;
        }
    
}
