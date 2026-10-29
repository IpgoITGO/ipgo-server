package com.ipgo.server.upload;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ipgo.upload.storage", havingValue = "s3")
class S3UploadConfiguration {
    @Bean
    S3Client s3Client() {
        return S3Client.create();
    }
}
