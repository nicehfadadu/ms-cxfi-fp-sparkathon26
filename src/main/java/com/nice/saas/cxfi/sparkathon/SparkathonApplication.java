package com.nice.saas.cxfi.sparkathon;

import com.nice.saas.cxfi.sparkathon.config.TopicAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(TopicAiProperties.class)
@EnableAsync
public class SparkathonApplication {

    public static void main(String[] args) {
        SpringApplication.run(SparkathonApplication.class, args);
    }
}
