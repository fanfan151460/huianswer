package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.documentUtil.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.lhsg.HuiAnswer.rag.knowledge.enums.FileStatus.*;
import static com.lhsg.HuiAnswer.rag.knowledge.enums.ParseStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements IKnowledgeDocumentService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf");

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final LocalDocumentStorage storage;
    private final TikaDocumentParser parser;
    private final Gson gson;
    private final IKnowledgeChunkService chunkService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file) {
        validateKbId(kbId);
        String name = validateFile(file);
        // 先拒绝不存在或已删除的知识库，避免无效请求也保存原文件。
        if (baseMapper.selectById(kbId) == null) {
            throw new ClientException("知识库不存在或已被删除");
        }
        // TODO 防止伪造文件扩展名
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String path = storage.save(kbId, file, extension);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("fileSize", file.getSize());
        metadata.addProperty("extension", extension);
        KnowledgeDocumentDO document = new KnowledgeDocumentDO().setKbId(kbId)
                .setDocName(name).setFilePath(path).setStatus(FILE_PENDING.status())
                .setMetadata(metadata.toString());

        // 短事务登记 pending；与删除知识库锁同一行，提交后再解析。
        transactionTemplate.execute(status -> {
            if (baseMapper.selectActiveForUpdate(kbId) == null) {
                throw new ClientException("知识库不存在或已被删除");
            }
            if (documentMapper.insert(document) != 1) {
                throw new ServiceException("登记文档失败");
            }
            return null;
        });

        ParsedDocument parsed;
        try (InputStream input = storage.open(path)) {
            parsed = parser.parse(input, name, extension);
            metadata.addProperty("parseStatus", PARSE_PARSED.status());
            metadata.addProperty("mediaType", parsed.mediaType());
            metadata.addProperty("textLength", parsed.text().length());
        } catch (Exception e) {
            return markFailed(document, metadata, PARSE_FAILED.status(), e, "读取或解析文档失败，请重试");
        }

        try {
            List<String> chunks = chunkService.toChunks(parsed.text());
            transactionTemplate.execute(status -> {
                // 解析期间知识库可能已删除；写入前重新检查并锁定。
                if (baseMapper.selectActiveForUpdate(kbId) == null) {
                    throw new ClientException("知识库不存在或已被删除");
                }
                // TODO 后续拆分向量化和写库，避免网络 IO 占用事务连接。
                chunkService.saveChunks(chunks, kbId, document.getId());
                metadata.addProperty("chunkCount", chunks.size());
                metadata.addProperty("parseStatus", PARSE_SUCCESS.status());
                document.setStatus(FILE_SUCCESS.status()).setMetadata(metadata.toString()).setUpdateTime(null);
                // updateById 使用实体上的 JsonbTypeHandler，且同步了响应对象。
                if (documentMapper.updateById(document) != 1) {
                    throw new ServiceException("更新文档状态失败");
                }
                return null;
            });
        } catch (Exception e) {
            // execute 已经结束并回滚，failed 在另一个短事务里提交。
            return markFailed(document, metadata, PARSE_PARSED.status(), e, "文档分块、向量化或入库失败，请重试");
        }
        return toRespDTO(document);
    }

    private KnowledgeDocumentRespDTO markFailed(KnowledgeDocumentDO document, JsonObject metadata,
                                                 String parseStatus, Exception cause, String fallbackMessage) {
        log.warn("文档导入失败，docId={}", document.getId(), cause);
        // 业务异常的提示可返回；数据库、文件系统等内部异常只记录日志。
        String message = fallbackMessage;
        if ((cause instanceof ClientException || cause instanceof ServiceException)
                && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message = cause.getMessage();
        }
        metadata.addProperty("parseStatus", parseStatus);
        metadata.addProperty("chunkCount", 0);
        metadata.addProperty("errorMessage", message);
        // 回滚不会恢复 Java 对象，显式覆盖可能留下的 success 状态和片段数量。
        document.setStatus(FILE_FAILED.status())
                .setMetadata(metadata.toString()).setUpdateTime(null);
        transactionTemplate.execute(status -> {
            if (documentMapper.updateById(document) != 1) {
                // 删除知识库已标记文档删除时，不把它重新恢复成有效文档。
                throw new ClientException("文档不存在或已被删除");
            }
            return null;
        });
        return toRespDTO(document);
    }

    @Override
    public List<KnowledgeDocumentRespDTO> listDocument(Long kbId) {
        validateKbId(kbId);
        if (baseMapper.selectById(kbId) == null) {
            throw new ClientException("知识库不存在");
        }
        return documentMapper.selectList(Wrappers.<KnowledgeDocumentDO>lambdaQuery()
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

    private KnowledgeDocumentRespDTO toRespDTO(KnowledgeDocumentDO document) {
        KnowledgeDocumentRespDTO response = new KnowledgeDocumentRespDTO().setId(document.getId())
                .setKbId(document.getKbId()).setDocName(document.getDocName()).setStatus(document.getStatus())
                .setCreateTime(document.getCreateTime()).setUpdateTime(document.getUpdateTime());
        if (document.getMetadata() != null) {
            try {
                DocumentMetadata metadata = gson.fromJson(document.getMetadata(), DocumentMetadata.class);
                if (metadata != null) {
                    response.setParseStatus(metadata.parseStatus)
                            .setTextLength(metadata.textLength)
                            .setChunkCount(metadata.chunkCount)
                            .setErrorMessage(metadata.errorMessage);
                }
            } catch (JsonParseException e) {
                throw new ServiceException("文档元数据格式错误");
            }
        }
        return response;
    }

    /**
     * 只接收响应需要的元数据字段，其它字段由 Gson 自动忽略。
     */
    private static class DocumentMetadata {
        private String parseStatus;
        private Integer textLength;
        private Integer chunkCount;
        private String errorMessage;
    }

}
