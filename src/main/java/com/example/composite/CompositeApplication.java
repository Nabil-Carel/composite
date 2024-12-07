package com.example.composite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.composite.config.CompositeApiProperties;

@SpringBootApplication
@EnableConfigurationProperties(CompositeApiProperties.class)
public class CompositeApplication {

	public static void main(String[] args) {
		SpringApplication.run(CompositeApplication.class, args);
	}

}
