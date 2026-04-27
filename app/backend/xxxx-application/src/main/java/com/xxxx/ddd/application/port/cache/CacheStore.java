package com.xxxx.ddd.application.port.cache;

public interface CacheStore {
    void setObject(String key, Object value);

    <T> T getObject(String key, Class<T> targetClass);

    void delete(String key);

    void setInt(String key, int value);

    int getInt(String key);

    Integer getIntOrNull(String key);

    Long increment(String key, long delta);

    long decreaseIntByLuaReturningRemaining(String key, int quantity);
}
