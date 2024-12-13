package com.odedia.analyzer;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.DEFAULT_CHAT_MEMORY_CONVERSATION_ID;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/pdf")
public class PDFAnalyzerService {
	private final Logger logger = LoggerFactory.getLogger(PDFAnalyzerService.class);

	private final ChatClient chatClient;
	private final ChatMemory chatMemory;
	private int totalChunks = 0; // Total number of chunks to be processed
	private int processedChunks = 0; // Number of chunks processed so far

	private VectorStore vectorStore;

	private JdbcService jdbcService;

	public PDFAnalyzerService(VectorStore vectorStore, 
								ChatClient.Builder chatClientBuilder, 
								FileService fileService, 
								ChatMemory chatMemory,
								JdbcService jdbcService)
			throws IOException {
		this.chatClient = chatClientBuilder.build();
		this.vectorStore = vectorStore;
		this.chatMemory = chatMemory;
		this.jdbcService = jdbcService;
	}

	@PostMapping("analyze")
	public ResponseEntity<Map<String, String>> analyze(@RequestParam("file") MultipartFile file) throws IOException {
		logger.info("File is {}", file.getOriginalFilename());
		List<Document> documents = null;
		try {
			ParagraphPdfDocumentReader pdfReader = new ParagraphPdfDocumentReader(file.getResource());
			documents = pdfReader.get();
		} catch (IllegalArgumentException e) {
			PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(file.getResource(),
					PdfDocumentReaderConfig.builder().withPageExtractedTextFormatter(ExtractedTextFormatter.builder().build())
							.withPagesPerDocument(1).build());
			documents = pdfReader.get();
		}
		

		var pdfToDocsSummary = """
				Input pdf read from %s
				Each pharagraph of the pdf was turned into a Document object
				%s total Document objects were created
				document id is %s
				document metadata is %s
				first document contents between the two dashed lines below
				---
				%s
				---
				""".formatted(file.getResource().getFilename(), documents.get(0).getId(),
				documents.get(0).getMetadata(), documents.size(), documents.get(0).getContent());

		TextSplitter tokenTextSplitter = new TokenTextSplitter();
		List<Document> chunks = tokenTextSplitter.apply(documents);

		logger.info("Clearing vector store before new PDF embedding.");
		
		jdbcService.clearVectorStore();
		
		logger.info("Done clearing vector store before new PDF embedding.");
		
		logger.info("Embedding {} chunks to vector store.", chunks.size());

		this.vectorStore.accept(chunks);

		Map<String, String> response = new HashMap<>();
		response.put("status", "success");
		response.put("message", "File uploaded successfully");
		response.put("result", "Processed " + chunks.size() + " chunks. ");
		return ResponseEntity.ok(response);
	}

	@PostMapping("/query")
	public Flux<String> queryPdf(@RequestBody String question) {
		logger.info("Received question: {}", question);

		return this.chatClient.prompt().
				advisors(
						new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults(), 
						"""
    Given the context information bellow , surrounded ---------------------, and provided
    history information and not prior knowledge, reply to the user comment.
    If the answer is not in the context, inform the user that you can't answer the question.

	---------------------
	{question_answer_context} 
	---------------------
						"""
								
								),
						new MessageChatMemoryAdvisor(chatMemory, DEFAULT_CHAT_MEMORY_CONVERSATION_ID, 10)
						)
				.user(question).stream().content();

	}

	@GetMapping("progress")
	public ResponseEntity<Progress> getProgress() {
		Progress progress = new Progress(totalChunks, processedChunks);
		return ResponseEntity.ok(progress);
	}

	public static class Progress {
		private int totalChunks;
		private int processedChunks;

		public Progress(int totalChunks, int processedChunks) {
			this.totalChunks = totalChunks;
			this.processedChunks = processedChunks;
		}

		public int getTotalChunks() {
			return totalChunks;
		}

		public int getProcessedChunks() {
			return processedChunks;
		}
	}
}
