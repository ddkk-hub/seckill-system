package com.ddk.seckill;

import com.ddk.seckill.entity.AsyncReceipt;
import com.ddk.seckill.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/stage2.sql,classpath:db/stage3.sql","seckill.mq.queue=seckill.test.orders.v5","logging.file.name=target/stage5-test-application.log"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("stage5")
@EnabledIfEnvironmentVariable(named="SECKILL_ENGINEERING_TEST",matches="true")
class EngineeringIntegrationTest {
    @Autowired MockMvc mvc;
    @MockBean ProtectedSeckillService service;
    @MockBean AsyncSeckillService async;
    @MockBean TokenBucketLimiter limiter;

    @Test void missingHeaderHasConsistentErrorAndTrace()throws Exception{
        var result=mvc.perform(post("/seckill/1").param("userId","1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("status").value(400)).andReturn();
        String trace=result.getResponse().getHeader("X-Trace-Id");assertNotNull(trace);UUID.fromString(trace);
        assertTrue(result.getResponse().getContentAsString().contains(trace));verifyNoInteractions(service);
    }
    @Test void invalidParameterIs400()throws Exception{
        mvc.perform(get("/product/not-a-number")).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
    }
    @Test void limiterHeaderSurvivesExceptionAdvice()throws Exception{
        doThrow(new RateLimitExceededException(1500)).when(limiter).purchase(anyString(),eq(1L));
        mvc.perform(post("/seckill/1").param("userId","1").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","2"))
            .andExpect(jsonPath("code").value("RATE_LIMITED"));verifyNoInteractions(service);
    }
    @Test void internalDetailsNeverLeakToResponse()throws Exception{
        when(async.product(1)).thenThrow(new IllegalStateException("PRIVATE_INTERNAL_DETAIL"));
        var result=mvc.perform(get("/product/1")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("code").value("INTERNAL_ERROR")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("PRIVATE_INTERNAL_DETAIL"));
        assertFalse(result.getResponse().getContentAsString().contains("IllegalStateException"));
    }
    @Test void databaseFailureIs503WithoutSqlDetails()throws Exception{
        when(async.product(1)).thenThrow(new DataAccessResourceFailureException("PRIVATE_SQL"));
        var result=mvc.perform(get("/product/1")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("code").value("DEPENDENCY_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("PRIVATE_SQL"));
    }
    @Test void missingResourceStays404()throws Exception{
        mvc.perform(get("/not-an-endpoint")).andExpect(status().isNotFound()).andExpect(jsonPath("code").value("RESOURCE_NOT_FOUND"));
    }
    @Test void unsupportedMethodStays405WithAllowHeader()throws Exception{
        mvc.perform(put("/product/1")).andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow"));
    }
    @Test void uncertainReceiptStillContainsBusinessRequestId()throws Exception{
        String id=UUID.randomUUID().toString();when(service.submit(eq(1L),eq(1L),anyString())).thenReturn(new AsyncReceipt(id,"UNKNOWN",null));
        mvc.perform(post("/seckill/1").param("userId","1").header("Idempotency-Key",UUID.randomUUID().toString()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("requestId").value(id)).andExpect(jsonPath("status").value("UNKNOWN"));
    }
    @Test void traceContextDoesNotLeakAcrossRequests()throws Exception{
        var a=mvc.perform(get("/test").header("X-Trace-Id","untrusted")).andExpect(status().isOk()).andReturn();
        var b=mvc.perform(get("/test")).andExpect(status().isOk()).andReturn();
        assertNotEquals("untrusted",a.getResponse().getHeader("X-Trace-Id"));
        assertNotEquals(a.getResponse().getHeader("X-Trace-Id"),b.getResponse().getHeader("X-Trace-Id"));assertNull(MDC.get("traceId"));
    }
    @Test void unsupportedAcceptRemains406InsteadOfBecoming500()throws Exception{
        String id=UUID.randomUUID().toString();when(async.result(id)).thenReturn(new AsyncReceipt(id,"SUCCESS",1L));
        mvc.perform(get("/seckill/result/"+id).accept(org.springframework.http.MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable()).andExpect(jsonPath("status").value(406))
            .andExpect(jsonPath("code").value("HTTP_REQUEST_REJECTED"));
    }
    @Test void healthIsExposedWithoutDetailsButEnvironmentIsNot()throws Exception{
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("status").value("UP"))
            .andExpect(jsonPath("components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }
}
