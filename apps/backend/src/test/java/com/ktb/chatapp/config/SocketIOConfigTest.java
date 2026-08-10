package com.ktb.chatapp.config;

import com.corundumstudio.socketio.SocketIOServer;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@TestPropertySource(properties = "socketio.enabled=false")
@Import(MongoTestContainer.class)
class SocketIOConfigTest {

    @MockitoBean
    private RedissonClient redissonClient;

    @Test
    void shouldNotLoadSocketIOBeansWhenDisabled(ApplicationContext context) {
        // SocketIOServer bean should not exist when socketio.enabled=false
        assertThrows(NoSuchBeanDefinitionException.class,
            () -> context.getBean(SocketIOServer.class));
    }
}
