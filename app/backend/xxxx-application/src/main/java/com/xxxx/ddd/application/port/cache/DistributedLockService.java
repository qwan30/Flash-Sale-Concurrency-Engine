package com.xxxx.ddd.application.port.cache;

public interface DistributedLockService {
    ApplicationDistributedLock getDistributedLock(String key);
}
