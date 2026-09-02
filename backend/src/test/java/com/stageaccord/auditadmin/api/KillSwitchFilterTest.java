package com.stageaccord.auditadmin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class KillSwitchFilterTest {
    @Test void activeUploadSwitchStopsMutationBeforeController()throws Exception{
        JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq("upload"))).thenReturn(true);
        var request=new MockHttpServletRequest("POST","/api/v1/uploads/00000000-0000-0000-0000-000000000000/completion");
        var response=new MockHttpServletResponse();FilterChain chain=mock(FilterChain.class);
        new KillSwitchFilter(jdbc).doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(423);verify(chain,never()).doFilter(request,response);
    }
}
