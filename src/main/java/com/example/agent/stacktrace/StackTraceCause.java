package com.example.agent.stacktrace;

/**
 * Java 异常 Cause 链中的一个节点。
 */
public record StackTraceCause(String exceptionType, String message) {
}