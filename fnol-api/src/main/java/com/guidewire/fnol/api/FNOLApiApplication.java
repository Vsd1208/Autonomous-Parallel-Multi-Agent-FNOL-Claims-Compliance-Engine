package com.guidewire.fnol.api;
import org.springframework.boot.*;import org.springframework.boot.autoconfigure.*;import org.springframework.boot.autoconfigure.domain.*;import org.springframework.context.annotation.*;import org.springframework.data.jpa.repository.config.*;
@SpringBootApplication(scanBasePackages="com.guidewire.fnol") @EntityScan("com.guidewire.fnol.api.persistence") @EnableJpaRepositories("com.guidewire.fnol.api.persistence")
public class FNOLApiApplication { public static void main(String[] args){SpringApplication.run(FNOLApiApplication.class,args);} }
