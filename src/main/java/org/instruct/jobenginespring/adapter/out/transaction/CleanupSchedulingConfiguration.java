package org.instruct.jobenginespring.adapter.out.transaction;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CleanupSchedulingConfiguration {
}
