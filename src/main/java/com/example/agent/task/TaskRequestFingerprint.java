package com.example.agent.task;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TaskRequestFingerprint {

	private static final String VERSION = "agent-task-request-v1";

	private TaskRequestFingerprint() {
	}

	/**
	 * requestedModelId 应由调用方先按接口规则规范化。 message 保留原文，避免修改日志、SQL 或异常堆栈证据。
	 */
	public static String calculate(String message, String requestedModelId) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");

			appendField(digest, VERSION);
			appendField(digest, message);
			appendField(digest, requestedModelId);

			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
		}
	}

	private static void appendField(MessageDigest digest, String value) {
		if (value == null) {
			// -1 表示 null，与长度为 0 的空字符串区分。
			digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
			return;
		}

		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

		// 使用长度前缀划分字段，避免直接拼接时产生字段边界歧义。
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

}