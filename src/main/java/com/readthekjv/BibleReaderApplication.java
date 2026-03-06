package com.readthekjv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for the KJV Bible Reader application.
 * A distraction-free Bible reading experience.
 */
@SpringBootApplication
@EnableScheduling   // activates @Scheduled methods (verse-of-day daily cron)
@EnableAsync        // activates @Async (verse-of-day non-blocking startup generation)
public class BibleReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(BibleReaderApplication.class, args);
    }
}
