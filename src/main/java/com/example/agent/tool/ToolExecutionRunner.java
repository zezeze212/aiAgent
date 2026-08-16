package com.example.agent.tool;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在独立线程池中执行具体 AgentTool，并执行工具级 timeout。
 *
 * ToolRegistry 负责： - 工具查找； - 参数校验； - 状态分类； - 结果包装。
 *
 * ToolExecutionRunner 只负责： - 把工具提交到独立线程池； - 等待执行结果； - 根据 ToolDefinition.timeoutMs 控制等待时间；
 * - 将异步线程里的原始异常重新抛回 Registry。
 */
@Component
public class ToolExecutionRunner {

	private final AsyncTaskExecutor toolExecutionExecutor;

	public ToolExecutionRunner(@Qualifier("toolExecutionExecutor") AsyncTaskExecutor toolExecutionExecutor) {

		this.toolExecutionExecutor = toolExecutionExecutor;
	}

	public String execute(AgentTool tool, Map<String, Object> arguments) {

		long timeoutMs = tool.definition().timeoutMs();

		/*
		 * 参数已经经过 ToolArgumentValidator。 这里创建一个快照，避免调用方在工具异步执行期间继续修改参数 Map。
		 */
		Map<String, Object> executionArguments = Map.copyOf(arguments);

		Future<String> future = toolExecutionExecutor.submit(() -> tool.execute(executionArguments));

		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			/*
			 * cancel(true) 会向正在运行工具的线程发送 interrupt。
			 *
			 * 注意：这是一种 best-effort 取消。 如果底层阻塞调用完全不响应中断，线程本身可能仍继续运行， 但 Agent 主链路不会继续等待它。
			 */
			future.cancel(true);

			throw new ToolTimeoutException(tool.name(), timeoutMs, e);
		}
		catch (InterruptedException e) {
			/*
			 * 当前等待工具结果的线程自己被中断时， 必须恢复中断标记，不能静默吞掉 InterruptedException。
			 */
			future.cancel(true);
			Thread.currentThread().interrupt();

			throw new IllegalStateException("等待工具执行结果时线程被中断，toolName=" + tool.name(), e);
		}
		catch (ExecutionException e) {
			/*
			 * Future.get() 会把工具线程内部异常包装成 ExecutionException。
			 *
			 * ToolBusinessException 等 RuntimeException 必须还原出来， Registry 才能继续正确识别
			 * BUSINESS_ERROR。
			 */
			Throwable cause = e.getCause();

			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}

			throw new IllegalStateException("工具执行发生异常，toolName=" + tool.name(), cause);
		}
	}

}