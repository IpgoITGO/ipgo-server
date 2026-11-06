package com.ipgo.server.billing;

import com.ipgo.server.DatabaseIntegrationTest;
import com.ipgo.server.infrastructure.BillingGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"ipgo.billing.enabled=false","ipgo.billing.worker-enabled=false"})
@AutoConfigureMockMvc
class BillingDisabledIntegrationTests extends DatabaseIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean BillingGateway gateway;
    @Test void disabledBillingNeedsNoKeysAndCannotCallProvider() throws Exception {
        mvc.perform(get("/api/billing/plans").with(jwt())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value("BILLING_DISABLED"));
        mvc.perform(post("/api/billing/webhooks/toss").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("code").value("BILLING_DISABLED"));
        verifyNoInteractions(gateway);
    }
}
