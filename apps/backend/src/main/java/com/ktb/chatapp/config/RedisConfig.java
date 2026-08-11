package com.ktb.chatapp.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    // Redisson 라이브러리 기본값(connectionPoolSize=64, connectionMinimumIdleSize=24,
    // subscriptionConnectionPoolSize=50)을 그대로 기본값으로 두되, 배포 환경의 실측
    // 결과에 따라 코드 변경 없이 조정할 수 있도록 환경변수로 뺀다. 500명 동시 접속을
    // 목표로 임의로 올리지 않고, 현재 기본값과 동일하게 시작한다.
    @Value("${redisson.connection-pool-size:64}")
    private int redissonConnectionPoolSize;

    @Value("${redisson.connection-minimum-idle-size:24}")
    private int redissonConnectionMinimumIdleSize;

    @Value("${redisson.subscription-connection-pool-size:50}")
    private int redissonSubscriptionConnectionPoolSize;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(DataRedisConnectionDetails connectionDetails) {
        var standalone = connectionDetails.getStandalone();

        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase())
                .setConnectionPoolSize(redissonConnectionPoolSize)
                .setConnectionMinimumIdleSize(redissonConnectionMinimumIdleSize)
                .setSubscriptionConnectionPoolSize(redissonSubscriptionConnectionPoolSize);

        String username = connectionDetails.getUsername();
        if (username != null && !username.isBlank()) {
            serverConfig.setUsername(username);
        }

        String password = connectionDetails.getPassword();
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }

        return Redisson.create(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        var objectMapper = JsonMapper.builder()
                .activateDefaultTyping(
                        typeValidator,
                        DefaultTyping.NON_FINAL_AND_RECORDS,
                        JsonTypeInfo.As.PROPERTY
                )
                .build();
        var serializer = new GenericJacksonJsonRedisSerializer(objectMapper);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }
}
