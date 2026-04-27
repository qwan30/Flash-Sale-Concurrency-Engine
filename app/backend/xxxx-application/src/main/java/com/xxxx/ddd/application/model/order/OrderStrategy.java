package com.xxxx.ddd.application.model.order;

public enum OrderStrategy {
    UNSAFE_DB,
    CONDITIONAL_DB,
    REDIS_LUA,
    REDIS_LUA_WITH_COMPENSATION
}
