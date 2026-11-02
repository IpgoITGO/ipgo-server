package com.ipgo.server.integration;

import com.ipgo.server.DatabaseIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ApiDocsDisabledTests extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void documentationIsNotPublicInTheDefaultConfiguration() throws Exception {
        for (String path : new String[] { "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs",
                "/v3/api-docs/swagger-config", "/v3/api-docs.yaml" }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
