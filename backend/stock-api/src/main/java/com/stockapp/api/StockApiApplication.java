package com.stockapp.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 极简版同花顺 - API 启动入口 */
@SpringBootApplication(scanBasePackages = "com.stockapp")
@MapperScan("com.stockapp.dao.mapper")
@EnableScheduling
public class StockApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockApiApplication.class, args);
    }
}
