package org.instruct.jobenginespring.adapter.in.http.operator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OperatorSecurityConfiguration {

    @Bean
    FilterRegistrationBean<OperatorSecurityFilter> operatorSecurityFilter(
            @Value("${job-engine.operator.enabled:false}") boolean enabled,
            @Value("${job-engine.operator.bearer-token:}") String bearerToken
    ) {
        if (enabled && bearerToken.length() < 32) {
            throw new IllegalStateException("job-engine operator bearer token must be at least 32 characters when enabled");
        }
        FilterRegistrationBean<OperatorSecurityFilter> registration = new FilterRegistrationBean<>(
                new OperatorSecurityFilter(enabled, bearerToken)
        );
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
