package com.kommserver;

import com.kommserver.bootstrap.PreBootActivation;
import com.kommserver.config.DataDir;
import com.kommserver.config.ReadyMarker;
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
		DataDir.init();
		ReadyMarker.clear();
		// Before Spring: a new installation is activated with the hub first, so the hub's certificate and ports are on
		// disk when Tomcat is configured and the first start is already HTTPS on the right port.
		PreBootActivation.run();
		SpringApplication.run(KommApplication.class, args);
	}
}
