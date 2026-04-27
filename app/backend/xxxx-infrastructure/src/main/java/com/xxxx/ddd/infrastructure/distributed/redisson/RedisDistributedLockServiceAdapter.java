package com.xxxx.ddd.infrastructure.distributed.redisson;

import com.xxxx.ddd.application.port.cache.ApplicationDistributedLock;
import com.xxxx.ddd.application.port.cache.DistributedLockService;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RedisDistributedLockServiceAdapter implements DistributedLockService {

    private final RedisDistributedService redisDistributedService;

    public RedisDistributedLockServiceAdapter(RedisDistributedService redisDistributedService) {
        this.redisDistributedService = redisDistributedService;
    }

    @Override
    public ApplicationDistributedLock getDistributedLock(String key) {
        RedisDistributedLocker locker = redisDistributedService.getDistributedLock(key);
        return new ApplicationDistributedLock() {
            @Override
            public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
                return locker.tryLock(waitTime, leaseTime, unit);
            }

            @Override
            public void unlock() {
                locker.unlock();
            }
        };
    }
}
