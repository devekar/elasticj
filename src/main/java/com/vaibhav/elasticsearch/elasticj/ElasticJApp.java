package com.vaibhav.elasticsearch.elasticj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.web.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ComponentScan
@EnableAutoConfiguration
//@EnableScheduling
public class ElasticJApp extends SpringBootServletInitializer {
    
    
    public static void main( String[] args ) {
        SpringApplication.run(ElasticJApp.class, args);
    }
}
