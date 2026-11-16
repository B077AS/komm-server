package com.kommserver;

import com.kommserver.config.DataDir;
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
		// Before Spring starts: the log configuration and every file path read the data folder.
		DataDir.init();
		SpringApplication.run(KommApplication.class, args);
	}
}
