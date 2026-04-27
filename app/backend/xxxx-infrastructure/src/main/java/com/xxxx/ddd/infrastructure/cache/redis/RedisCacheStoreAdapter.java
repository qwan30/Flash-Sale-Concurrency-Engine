package com.xxxx.ddd.infrastructure.cache.redis;

import com.xxxx.ddd.application.port.cache.CacheStore;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class RedisCacheStoreAdapter implements CacheStore {

    private final RedisInfrasService redisInfrasService;

    public RedisCacheStoreAdapter(RedisInfrasService redisInfrasService) {
        this.redisInfrasService = redisInfrasService;
    }

    @Override
    public void setObject(String key, Object value) {
        redisInfrasService.setObject(key, value);
    }

    @Override
    public <T> T getObject(String key, Class<T> targetClass) {
        return redisInfrasService.getObject(key, targetClass);
    }

    @Override
    public void delete(String key) {
        redisInfrasService.delete(key);
    }

    @Override
    public void setInt(String key, int value) {
        redisInfrasService.setInt(key, value);
    }

    @Override
    public int getInt(String key) {
        return redisInfrasService.getInt(key);
    }

    @Override
    public Integer getIntOrNull(String key) {
        return redisInfrasService.getIntOrNull(key);
    }

    @Override
    public Long increment(String key, long delta) {
        return redisInfrasService.increment(key, delta);
    }

    @Override
    public long decreaseIntByLuaReturningRemaining(String key, int quantity) {
        String luaScript = "local stock = tonumber(redis.call('GET', KEYS[1])); " +
                "if (stock == nil) then return -2; end; " +
                "if (stock >= tonumber(ARGV[1])) then " +
                "   redis.call('SET', KEYS[1], stock - tonumber(ARGV[1])); " +
                "   return stock - tonumber(ARGV[1]); " +
                "end; " +
                "return -1; ";
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(luaScript, Long.class);
        Long result = redisInfrasService.getRedisTemplate()
                .execute(redisScript, Collections.singletonList(key), quantity);
        return result == null ? -2 : result;
    }
}
