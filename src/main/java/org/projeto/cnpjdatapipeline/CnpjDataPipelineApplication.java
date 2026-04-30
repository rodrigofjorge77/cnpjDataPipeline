package org.projeto.cnpjdatapipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class CnpjDataPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CnpjDataPipelineApplication.class, args);
    }
}
