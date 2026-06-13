package com.maiya.persistence.example;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用程序启动类 Spring Boot 应用的入口，负责扫描并加载 Mapper 接口
 *
 * @author 萨博
 */
@SpringBootApplication
@MapperScan("com.maiya.persistence.example.mapper")
public class Application {

    /**
     * 应用程序入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
