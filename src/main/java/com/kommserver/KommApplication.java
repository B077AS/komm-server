package com.kommserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Locale;

@EnableScheduling
@SpringBootApplication(exclude = {
		UserDetailsServiceAutoConfiguration.class
})
public class KommApplication {
	public static void main(String[] args) {
		Locale.setDefault(Locale.ENGLISH);
		SpringApplication.run(KommApplication.class, args);
	}
}
