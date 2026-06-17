package com.automata.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JiraTicket {
    private String id;
    private String key;
    private String summary;
    private String description;
    private String priority;
}
