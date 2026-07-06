package com.example.agent.mapper;

import com.example.agent.dto.AgentRunStatsResponse;
import com.example.agent.dto.ToolStatsItem;
import com.example.agent.entity.AgentRunLog;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentRunLogMapper {

    int insert(AgentRunLog runLog);

    AgentRunLog selectByTraceId(@Param("traceId") String traceId);

    Long countByCondition(@Param("toolName") String toolName,
                          @Param("success") Integer success,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);

    List<AgentRunLog> selectPageByCondition(@Param("offset") Integer offset,
                                            @Param("pageSize") Integer pageSize,
                                            @Param("toolName") String toolName,
                                            @Param("success") Integer success,
                                            @Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    AgentRunStatsResponse selectOverallStats(@Param("toolName") String toolName,
                                             @Param("success") Integer success,
                                             @Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime);

    List<ToolStatsItem> selectToolStats(@Param("toolName") String toolName,
                                        @Param("success") Integer success,
                                        @Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);
}