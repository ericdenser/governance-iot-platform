package com.eric.agentgrpc.config;

import com.eric.agent.grpc.messaging.MessagingGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GrpcChannelConfig {

    @Value("${grpc.broker.host}")
    private String host;

    @Value("${grpc.broker.port}")
    private int port;

    @Value("${grpc.broker.tls:false}")
    private boolean tls;

    private ManagedChannel channel;

    @Bean
    public ManagedChannel managedChannel() {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
        if (tls) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        channel = builder.build();
        return channel;
    }

    @Bean
    public MessagingGrpc.MessagingStub messagingAsyncStub(ManagedChannel channel) {
        return MessagingGrpc.newStub(channel);
    }

    @Bean
    public MessagingGrpc.MessagingBlockingStub messagingBlockingStub(ManagedChannel channel) {
        return MessagingGrpc.newBlockingStub(channel);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
