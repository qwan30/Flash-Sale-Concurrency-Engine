package com.xxxx.ddd.infrastructure.distributed.redisson.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Value("${app.redisson.mode:single}")
    private String mode;

    @Value("${app.redisson.single-address:redis://127.0.0.1:6319}")
    private String singleAddress;

    @Value("${app.redisson.password:}")
    private String password;

    @Value("${app.redisson.sentinel.master:mymaster}")
    private String sentinelMaster;

    @Value("${app.redisson.sentinel.nodes:redis://localhost:26379,redis://localhost:26380,redis://localhost:26381}")
    private String sentinelNodes;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        if ("sentinel".equalsIgnoreCase(mode)) {
            var sentinelConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(sentinelNodes.split(","))
                    .setCheckSentinelsList(false)
                    .setDatabase(0)
                    .setMasterConnectionPoolSize(50)
                    .setMasterConnectionMinimumIdleSize(10)
                    .setSlaveConnectionPoolSize(50)
                    .setSlaveConnectionMinimumIdleSize(10);
            if (StringUtils.hasText(password)) {
                sentinelConfig.setPassword(password);
            }
        } else {
            var singleConfig = config.useSingleServer()
                    .setAddress(singleAddress)
                    .setConnectionPoolSize(50)
                    .setConnectionMinimumIdleSize(10)
                    .setDatabase(0);
            if (StringUtils.hasText(password)) {
                singleConfig.setPassword(password);
            }
        }
        return Redisson.create(config);
    }
}
