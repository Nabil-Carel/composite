package com.example.composite.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.jta.SimpleTransactionFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableTransactionManagement
public class TransactionPropagationConfig {
    @Bean
    public RestTemplate compositeRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new TransactionPropagationInterceptor());
        return restTemplate;
    }

}
