package com.example.composite.config;

import com.example.composite.config.filter.CompositeRequestFilter;
import com.example.composite.model.ResponseTracker;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class Config {

    @Bean("compositeObjectMapper")
    public ObjectMapper objectMapper(){
        return new ObjectMapper();
    }

    @Bean
    public FilterRegistrationBean<CompositeRequestFilter> compositeFilter(CompositeRequestFilter filter) {
        FilterRegistrationBean<CompositeRequestFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(filter);
        registrationBean.addUrlPatterns("/api/composite/execute");
        registrationBean.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return registrationBean;
    }

    @Bean
    public ConcurrentMap<String, ResponseTracker> responseStore(){
        return new ConcurrentHashMap<>();
    }
}
