package com.example.agent.rag.vector;

/**
 * Manifest 中的一条知识源声明。
 *
 * @param source classpath 中的文档路径
 * @param title 文档标题
 * @param category 知识类别
 * @param visibility 可见范围
 */
public record KnowledgeSource(String source, String title, String category, String visibility) {

}