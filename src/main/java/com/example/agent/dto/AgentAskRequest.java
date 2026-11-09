package com.example.agent.dto;

import lombok.Data;

@Data
public class AgentAskRequest {

	private String message;

	/**
	 * 业务模型标识；未填写时使用服务端默认模型。
	 */
	private String modelId;

}