package com.example.agent.mapper;

import com.example.agent.entity.AgentRunLog;
import org.apache.ibatis.annotations.Param;
import com.example.agent.dto.AgentRunStatsResponse;
import com.example.agent.dto.ToolStatsItem;
import java.util.List;

public interface AgentRunLogMapper {

    int insert(AgentRunLog runLog);

    AgentRunLog selectByTraceId(@Param("traceId") String traceId);

    Long countAll();

    List<AgentRunLog> selectPage(@Param("offset") Integer offset,
                                 @Param("pageSize") Integer pageSize);


    Long countByCondition(@Param("toolName") String toolName,
                          @Param("success") Integer success);

    List<AgentRunLog> selectPageByCondition(@Param("offset") Integer offset,
                                            @Param("pageSize") Integer pageSize,
                                            @Param("toolName") String toolName,
                                            @Param("success") Integer success);

    AgentRunStatsResponse selectOverallStats(@Param("toolName") String toolName,
                                             @Param("success") Integer success);

    List<ToolStatsItem> selectToolStats(@Param("toolName") String toolName,
                                        @Param("success") Integer success);
}