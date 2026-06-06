package com.luyublog.aidemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.luyublog.aidemo.infrastructure.persistence.mysql")
public class AidemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AidemoApplication.class, args);
    }

}
