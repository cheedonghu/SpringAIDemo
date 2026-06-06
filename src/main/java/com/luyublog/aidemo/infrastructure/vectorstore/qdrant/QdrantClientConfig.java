package com.luyublog.aidemo.infrastructure.vectorstore.qdrant;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Qdrant gRPC 客户端 bean。
 *
 * <p>注意端口：6334 是 gRPC，6333 是 REST。Java 客户端只走 gRPC。
 * Docker 启动时确保 {@code -p 6334:6334} 也映射出来。
 *
 * <p>自定义 {@link ManagedChannel}：默认 {@code QdrantGrpcClient.newBuilder(host, port, useTls)}
 * 内部建出的 channel 不开 keepalive，碰到 NAT / 防火墙 / 云 LB 把空闲 TCP 静默丢弃时，
 * gRPC 仍以为连接活着，下次发数据会卡住 19s 左右才报 {@code UNAVAILABLE: Network closed for unknown reason}。
 * 这里显式构造 channel，开启 keepalive + idleTimeout：
 * <ul>
 *   <li>{@code keepAliveTime}：每隔 N 秒在空闲连接上发 HTTP/2 PING，探活</li>
 *   <li>{@code keepAliveTimeout}：PING 之后 N 秒收不到 ACK 即判死、重建</li>
 *   <li>{@code keepAliveWithoutCalls}：没有进行中 RPC 时也保持探活（否则首次请求仍可能踩到死连接）</li>
 *   <li>{@code idleTimeout}：连接彻底没流量 N 秒后转 IDLE，下次调用会重建——给 keepalive 兜底</li>
 * </ul>
 *
 * <p>{@code QdrantClient.close()} 默认会一并关掉它持有的 channel，所以不用单独管 channel 生命周期。
 */
@Configuration
public class QdrantClientConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientConfig.class);

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(
            @Value("${app.qdrant.host:localhost}") String host,
            @Value("${app.qdrant.grpc-port:6334}") int grpcPort,
            @Value("${app.qdrant.use-tls:false}") boolean useTls,
            @Value("${app.qdrant.api-key:}") String apiKey,
            @Value("${app.qdrant.keepalive-seconds:30}") long keepaliveSeconds,
            @Value("${app.qdrant.keepalive-timeout-seconds:10}") long keepaliveTimeoutSeconds,
            @Value("${app.qdrant.idle-timeout-seconds:300}") long idleTimeoutSeconds) {

        log.info("init QdrantClient {}:{} tls={} apiKey={} keepalive={}s/{}s idle={}s",
                host, grpcPort, useTls,
                StringUtils.hasText(apiKey) ? "<set>" : "<empty>",
                keepaliveSeconds, keepaliveTimeoutSeconds, idleTimeoutSeconds);

        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, grpcPort)
                .keepAliveTime(keepaliveSeconds, TimeUnit.SECONDS)
                .keepAliveTimeout(keepaliveTimeoutSeconds, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(idleTimeoutSeconds, TimeUnit.SECONDS);
        if (useTls) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }
        ManagedChannel channel = channelBuilder.build();

        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(channel);
        if (StringUtils.hasText(apiKey)) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }
}
