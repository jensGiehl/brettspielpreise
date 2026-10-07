package de.agiehl.bgprices;

import de.agiehl.bgprices.config.PriceProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(PriceProperties.class)
@EnableScheduling
public class BgPricesApplication {
    public static void main(String[] args) {
        SpringApplication.run(BgPricesApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
