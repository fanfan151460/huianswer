package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.storage.LocalDocumentStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements IKnowledgeDocumentService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int PREVIEW_CHARACTERS = 2000;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf");

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final LocalDocumentStorage storage;
    private final TikaDocumentParser parser;
    private final ObjectMapper objectMapper;
    private final IKnowledgeChunkService chunkService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file) {
        validateKbId(kbId);
        String name = validateFile(file);
        // TODO 防止伪造文件后缀
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);

        // 当前与知识库删除使用同一行的数据库锁，事务结束时释放。
        if (knowledgeBaseMapper.selectActiveForUpdate(kbId) == null) {
            throw new ClientException("知识库不存在");
        }
        String path = storage.save(kbId, file, extension);
        KnowledgeDocumentDO document = new KnowledgeDocumentDO().setKbId(kbId)
                .setDocName(name).setFilePath(path).setStatus("pending");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileSize", file.getSize());
        metadata.put("extension", extension);

        // 读取本地保存的文件
        ParsedDocument parsed = null;
        String errorMessage = null;
        try (InputStream input = storage.open(path)) {
            parsed = parser.parse(input, name, extension);
            metadata.put("parseStatus", "parsed");
            metadata.put("mediaType", parsed.mediaType());
            metadata.put("textLength", parsed.text().length());
        } catch (ClientException e) {
            // 保留失败记录和原文件，便于观察和后续重试，不谎报导入成功。
            document.setStatus("failed");
            errorMessage = e.getMessage();
            metadata.put("parseStatus", "failed");
            metadata.put("errorMessage", errorMessage);
        } catch (IOException e) {
            throw new ServiceException("读取已保存的原文件失败");
        }
        if (parsed != null) {
            // 本课先分块并记录数量，下一课接入 Embedding 后再调用 saveChunks 入库。
            metadata.put("chunkCount", chunkService.toChunks(parsed.text()).size());
        }
        document.setMetadata(toJson(metadata));

        // 保存到数据库
        if (knowledgeDocumentMapper.insert(document) != 1) {
            throw new ServiceException("登记文档失败");
        }
        KnowledgeDocumentRespDTO response = toRespDTO(document);
        if (parsed != null) {
            String text = parsed.text();


            // 避免截断 Unicode 代理对。预览长度仍以 Java 字符为单位。
            int end = Math.min(text.length(), PREVIEW_CHARACTERS);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            response.setTextPreview(text.substring(0, end))
                    .setPreviewTruncated(end < text.length());
        }
        return response.setErrorMessage(errorMessage);
    }

    @Override
    public List<KnowledgeDocumentRespDTO> listDocument(Long kbId) {
        validateKbId(kbId);
        if (knowledgeBaseMapper.selectById(kbId) == null) {
            throw new ClientException("知识库不存在");
        }
        return knowledgeDocumentMapper.selectList(Wrappers.<KnowledgeDocumentDO>lambdaQuery()
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .orderByDesc(KnowledgeDocumentDO::getId))
                .stream().map(this::toRespDTO).toList();
    }

    private void validateKbId(Long kbId) {
        if (kbId == null || kbId <= 0) {
            throw new ClientException("知识库ID必须是正整数");
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ClientException("请选择非空文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ClientException("文件不能超过10MB");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new ClientException("文件名不能为空");
        }
        // 去掉浏览器可能携带的路径，只保留展示名；存储组件另行生成 UUID。
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ClientException("文件名不合法或超过255个字符");
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new ClientException("目前只支持txt、md和带文字的pdf文件");
        }
        return name;
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new ServiceException("记录解析信息失败");
        }
    }

    private KnowledgeDocumentRespDTO toRespDTO(KnowledgeDocumentDO document) {
        KnowledgeDocumentRespDTO response = new KnowledgeDocumentRespDTO().setId(document.getId())
                .setKbId(document.getKbId()).setDocName(document.getDocName()).setStatus(document.getStatus())
                .setCreateTime(document.getCreateTime()).setUpdateTime(document.getUpdateTime());
        if (document.getMetadata() != null) {
            try {
                var metadata = objectMapper.readTree(document.getMetadata());
                response.setParseStatus(metadata.path("parseStatus").asText(null));
                if (metadata.has("textLength")) {
                    response.setTextLength(metadata.path("textLength").asInt());
                }
                if (metadata.has("chunkCount")) {
                    response.setChunkCount(metadata.path("chunkCount").asInt());
                }
                response.setErrorMessage(metadata.path("errorMessage").asText(null));
            } catch (JsonProcessingException e) {
                throw new ServiceException("文档元数据格式错误");
            }
        }
        return response;
    }
}
