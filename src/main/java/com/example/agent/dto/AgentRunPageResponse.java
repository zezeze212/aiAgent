package com.example.agent.dto;

import com.example.agent.entity.AgentRunLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunPageResponse {

    private Integer pageNum;

    private Integer pageSize;

    private Long total;

    private List<AgentRunListItemResponse> list;
}