package com.example.agent.stacktrace;

/**
 * Java StackTrace 中的一条调用栈帧。
 */
public record StackTraceFrame(String className, String methodName, String fileName, Integer lineNumber,
		String location) {
}