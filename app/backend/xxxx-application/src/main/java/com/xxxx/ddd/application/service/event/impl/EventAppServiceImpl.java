package com.xxxx.ddd.application.service.event.impl;

import com.xxxx.ddd.application.service.event.EventAppService;
import com.xxxx.ddd.domain.service.HiDomainService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Minimal sample application service retained from the original scaffold.
 */
@Service
public class EventAppServiceImpl implements EventAppService {

    @Resource
    private HiDomainService hiDomainService;
    @Override
    public String sayHi(String who) {
        return hiDomainService.sayHi(who);
    }
}
