package com.jobfinder.web;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jobfinder.db.SqlLog;
import com.jobfinder.config.AppSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

/** Development aid: adds the statements executed by a request as the X-SQL-Log response header. */
@Component
public class SqlLogFilter extends OncePerRequestFilter {

    private final AppSettings settings;

    public SqlLogFilter(AppSettings settings) {
        this.settings = settings;
    }

    private final ObjectMapper asciiMapper =
            JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private static final int MAX_HEADER_BYTES = 6 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!settings.sqlLogEnabled()) {
            SqlLog.reset();
            try {
                chain.doFilter(request, response);
            } finally {
                SqlLog.reset();
            }
            return;
        }
        SqlLog.reset();
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            if (!SqlLog.entries().isEmpty()) {
                String json = asciiMapper.writeValueAsString(SqlLog.entries());
                if (json.length() <= MAX_HEADER_BYTES) {
                    wrapper.setHeader("X-SQL-Log", json);
                } else {
                    wrapper.setHeader("X-SQL-Log",
                            "[\"" + SqlLog.entries().size() + " queries (header truncated)\"]");
                }
            }
        } finally {
            wrapper.copyBodyToResponse();
            SqlLog.reset();
        }
    }
}
