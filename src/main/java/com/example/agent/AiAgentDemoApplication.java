package com.example.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.example.agent.mapper")
@SpringBootApplication
public class AiAgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentDemoApplication.class, args);
    }

}
