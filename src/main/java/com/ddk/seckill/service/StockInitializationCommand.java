package com.ddk.seckill.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seckill.initialize-product")
public class StockInitializationCommand implements ApplicationRunner {
    private final StockInitializer initializer;
    private final ConfigurableApplicationContext context;
    private final long id;

    public StockInitializationCommand(StockInitializer initializer, ConfigurableApplicationContext context,
            @Value("${seckill.initialize-product}") long id) {
        this.initializer = initializer; this.context = context; this.id = id;
    }

    @Override public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Initialization must run offline with --spring.main.web-application-type=none");
        }
        var product = initializer.initialize(id);
        System.out.println("Inventory ready: product=" + id + ", remaining=" + product.getStock());
        context.close();
    }
}
