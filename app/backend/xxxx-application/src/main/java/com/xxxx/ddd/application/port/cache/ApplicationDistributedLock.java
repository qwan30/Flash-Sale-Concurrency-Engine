package com.xxxx.ddd.application.port.cache;

import java.util.concurrent.TimeUnit;

public interface ApplicationDistributedLock {
    boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException;

    void unlock();
}
