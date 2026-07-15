package com.nice.saas.cxfi.sparkathon;

import com.nice.saas.cxfi.sparkathon.config.TopicAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TopicAiProperties.class)
public class SparkathonApplication {

    public static void main(String[] args) {
        SpringApplication.run(SparkathonApplication.class, args);
    }
}
