package com.automata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
public class AuditLogService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void logAuditEvent(String ticketKey, String eventType, String status, Map<String, Object> metadata) {
        try {
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("type", "AUDIT_LOG");
            logEntry.put("ticketKey", ticketKey);
            logEntry.put("timestamp", Instant.now().toString());
            logEntry.put("eventType", eventType);
            logEntry.put("status", status);
            logEntry.put("metadata", metadata != null ? metadata : Map.of());

            String jsonLog = objectMapper.writeValueAsString(logEntry);
            // Print directly to console for structured extraction
            System.out.println(jsonLog);
        } catch (Exception e) {
            log.error("Failed to generate audit log: {}", e.getMessage());
        }
    }

    public String hashData(String data) {
        if (data == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "hash-failed";
        }
    }
}
