package com.example.agent.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 加载并校验本地知识 Manifest 和 Markdown。
 *
 * 只有 Manifest 显式列出的 classpath Markdown 才能进入向量库。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeManifestLoader {

	private static final String MANIFEST_PATH = "docs/knowledge/manifest.json";

	private final ObjectMapper objectMapper;

	public KnowledgeManifest loadManifest() {
		Resource resource = requireResource(MANIFEST_PATH);

		try (InputStream inputStream = resource.getInputStream()) {
			KnowledgeManifest manifest = objectMapper.readValue(inputStream, KnowledgeManifest.class);
			validateManifest(manifest);
			return manifest;
		}
		catch (IOException e) {
			throw new IllegalStateException("读取知识 Manifest 失败，classpath=" + MANIFEST_PATH, e);
		}
	}

	public String readMarkdown(KnowledgeSource source) {
		validateSource(source);

		Resource resource = requireResource(source.source());

		try (InputStream inputStream = resource.getInputStream()) {
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException("读取知识文档失败，classpath=" + source.source(), e);
		}
	}

	private void validateManifest(KnowledgeManifest manifest) {
		if (manifest == null) {
			throw new IllegalStateException("知识 Manifest 不能为空");
		}

		if (isBlank(manifest.knowledgeVersion())) {
			throw new IllegalStateException("knowledgeVersion 不能为空");
		}

		if (manifest.documents().isEmpty()) {
			throw new IllegalStateException("Manifest 至少需要声明一篇知识文档");
		}

		Set<String> sources = new HashSet<>();

		for (KnowledgeSource source : manifest.documents()) {
			validateSource(source);

			if (!sources.add(source.source())) {
				throw new IllegalStateException("Manifest 中存在重复知识源，source=" + source.source());
			}
		}
	}

	private void validateSource(KnowledgeSource source) {
		if (source == null) {
			throw new IllegalStateException("知识源不能为空");
		}

		if (isBlank(source.source()) || !source.source().startsWith("docs/knowledge/")
				|| !source.source().endsWith(".md")) {
			throw new IllegalStateException("知识源必须是 docs/knowledge/ 下的 Markdown，source=" + source.source());
		}

		if (isBlank(source.title())) {
			throw new IllegalStateException("知识源标题不能为空，source=" + source.source());
		}

		if (isBlank(source.category())) {
			throw new IllegalStateException("知识源类别不能为空，source=" + source.source());
		}

		if (!"public".equals(source.visibility())) {
			throw new IllegalStateException("M5 种子知识只允许 public 文档，source=" + source.source());
		}
	}

	private Resource requireResource(String path) {
		Resource resource = new ClassPathResource(path);

		if (!resource.exists()) {
			throw new IllegalStateException("classpath 资源不存在，path=" + path);
		}

		return resource;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}