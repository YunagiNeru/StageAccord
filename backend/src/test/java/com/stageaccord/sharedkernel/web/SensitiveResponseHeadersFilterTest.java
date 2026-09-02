package com.stageaccord.sharedkernel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class SensitiveResponseHeadersFilterTest {
    @Test
    void authResponsesCannotBeCachedOrLeakReferrers() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/sessions");
        var response = new MockHttpServletResponse();

        new SensitiveResponseHeadersFilter().doFilterInternal(request, response, mock(FilterChain.class));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
