package com.example.agent.mapper;

import com.example.agent.entity.AgentStepLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AgentStepLogMapper {

    int insert(AgentStepLog stepLog);

    List<AgentStepLog> selectByTraceId(@Param("traceId") String traceId);
}