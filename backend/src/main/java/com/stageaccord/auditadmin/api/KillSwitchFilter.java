package com.stageaccord.auditadmin.api;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class KillSwitchFilter extends OncePerRequestFilter {
    private static final Pattern WORKSPACE=Pattern.compile("/workspaces/([0-9a-fA-F-]{36})(?:/|$)");
    private final JdbcTemplate jdbc;public KillSwitchFilter(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return HttpMethod.GET.matches(request.getMethod())||
            request.getRequestURI().contains("/kill-switches/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException{String path=request.getRequestURI();String feature=path.contains("/auth/")?"authentication":
            path.contains("/uploads")||path.contains("/files/")?"upload":path.contains("/public/services/")&&path.endsWith("/requests")?"intake":"critical_writes";
        var matcher=WORKSPACE.matcher(path);UUID workspace=matcher.find()?UUID.fromString(matcher.group(1)):null;try{boolean stopped=workspace==null?
                jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM (SELECT DISTINCT ON (workspace_id) action FROM audit.kill_switch_event "
                        +"WHERE feature=? ORDER BY workspace_id,occurred_at DESC) latest WHERE action='stopped')",Boolean.class,feature):
                jdbc.query("SELECT action FROM audit.kill_switch_event WHERE workspace_id=? AND feature=? ORDER BY occurred_at DESC LIMIT 1",(r,n)->r.getString(1),workspace,feature)
                .stream().findFirst().map("stopped"::equals).orElse(false);if(stopped){response.sendError(HttpStatus.LOCKED.value(),"FEATURE_WRITE_STOPPED");return;}}
        catch(DataAccessException failure){response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),"KILL_SWITCH_STATE_UNAVAILABLE");return;}chain.doFilter(request,response);}
}
