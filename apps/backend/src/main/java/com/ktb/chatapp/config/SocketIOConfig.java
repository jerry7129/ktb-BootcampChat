package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.data.redis.core.RedisTemplate;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Value("${socketio.server.origin:*}")
    private String origin;

    // 500명 규모의 "동시 접속 순간" 부하를 겨냥한 값들. acceptBackLog는 OS가 accept() 대기시킬 수
    // 있는 미처리 연결 큐 크기라 이 값을 넘는 순간 도착한 연결은 애플리케이션 로그 없이 조용히
    // 거부/리셋된다 — 기존 10은 burst connect 시나리오에 명백히 부족해서 올린다. tcpNoDelay는
    // 실시간 채팅처럼 작은 메시지를 자주 보내는 경우 Nagle 알고리즘의 배칭 지연(최대 40ms)이
    // 오히려 손해라 꺼둔다.
    @Value("${socketio.server.accept-backlog:1024}")
    private int acceptBackLog;

    @Value("${socketio.server.tcp-no-delay:true}")
    private boolean tcpNoDelay;

    // corundum-socketio는 0을 "Netty 기본값 사용"(NioEventLoopGroup 기준 CPU 코어 수 * 2)으로
    // 해석한다. 기존 코드는 이 값을 아예 설정하지 않아 항상 0(라이브러리 기본)이었는데, 실측
    // 없이 임의로 올리지 않고 우선 같은 기본값을 유지한 채 환경변수로 조정 가능하게만 만든다.
    @Value("${socketio.server.boss-threads:0}")
    private int bossThreads;

    @Value("${socketio.server.worker-threads:0}")
    private int workerThreads;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener, MeterRegistry meterRegistry, RedissonClient redissonClient) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);
        config.setBossThreads(bossThreads);
        config.setWorkerThreads(workerThreads);

        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(tcpNoDelay);
        socketConfig.setAcceptBackLog(acceptBackLog);
        socketConfig.setTcpSendBufferSize(4096);
        socketConfig.setTcpReceiveBufferSize(4096);
        config.setSocketConfig(socketConfig);

        config.setOrigin(origin);

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(10000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(new RedissonStoreFactory(redissonClient)); // Redis pub/sub 기반, 멀티노드 지원

        log.info("Socket.IO server configured on {}:{} with {} boss threads and {} worker threads",
                 host, port, config.getBossThreads(), config.getWorkerThreads());
        var socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addEventInterceptor((client, name, data, ack) -> {
            // 이벤트 발생 빈도 수집
            Counter.builder("socketio.events.total")
                .description("Total Socket.IO events received")
                .tag("event_type", name)
                .register(meterRegistry)
                .increment();
        });
        
        return socketIOServer;
    }
    
    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }
    
    // Redis 기반 저장소, 멀티노드 환경에서 ConnectedUsers/UserRooms 공유
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore(RedisTemplate<String, Object> redisTemplate) {
        return new RedisChatDataStore(redisTemplate);
    }
}
