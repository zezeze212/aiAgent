package com.example.agent.stacktrace;

import java.util.List;

/**
 * Java StackTrace 的确定性结构化解析结果。
 */
public record StackTraceAnalysisResult(String topExceptionType, String topExceptionMessage, String rootCauseType,
		String rootCauseMessage, int causeDepth, List<StackTraceCause> causeChain,
		StackTraceFrame firstApplicationFrame, List<StackTraceFrame> applicationFrames, int totalFrameCount,
		int applicationFrameCount) {
}