package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.duplicate.NoDuplicateSubmit;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.DocumentTypeValidator;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.ParsedDocument;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.document.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.DocumentMetadataDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeChunkRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
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
import java.util.Objects;
import java.util.Set;

import static com.lhsg.HuiAnswer.rag.knowledge.enums.FileStatus.*;
import static com.lhsg.HuiAnswer.rag.knowledge.enums.ParseStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements IKnowledgeDocumentService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "pdf", "doc", "docx");

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final LocalDocumentStorage storage;
    private final TikaDocumentParser parser;
    private final DocumentTypeValidator typeValidator;
    private final Gson gson;
    private final IKnowledgeChunkService chunkService;
    private final TransactionTemplate transactionTemplate;
    private final DocumentProducer producer;

    @Override
    @NoDuplicateSubmit
    public KnowledgeDocumentRespDTO uploadDocument(Long kbId, MultipartFile file) {
        Long createdBy = UsersContext.requireUserId();
        validateKbId(kbId);
        String name = validateFile(file);
        // 先拒绝不存在或已删除的知识库，避免无效请求也保存原文件。
        if (baseMapper.selectById(kbId) == null) {
            throw new ClientException("知识库不存在或已被删除");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        // 通过实际内容校验后再落盘、登记文档和发送消息。
        String mediaType = typeValidator.validate(file, extension);
        String path = storage.save(kbId, file, extension);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("fileSize", file.getSize());
        metadata.addProperty("extension", extension);
        metadata.addProperty("mediaType", mediaType);
        KnowledgeDocumentDO document = new KnowledgeDocumentDO().setKbId(kbId).setCreatedBy(createdBy)
                .setDocName(name).setFilePath(path).setStatus(FILE_PENDING.status()).setEnabled(0)
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

        return enqueue(document);
    }

    private KnowledgeDocumentRespDTO enqueue(KnowledgeDocumentDO document) {
        DocumentMsg msg = new DocumentMsg().setKbId(document.getKbId()).setDocId(document.getId());
        try {
            producer.send(msg);
        } catch (Exception e) {
            log.error("发送文档任务失败，docId={}", document.getId(), e);
            saveFailure(msg, readMetadata(document), "文档消息发送失败，请稍后重试");
        }
        return getDocument(document.getKbId(), document.getId());
    }

    @Override
    public KnowledgeDocumentRespDTO getDocument(Long kbId, Long docId) {
        return toRespDTO(requireDocument(kbId, docId));
    }

    @Override
    public KnowledgeDocumentRespDTO retryDocument(Long kbId, Long docId) {
        KnowledgeDocumentDO document = transactionTemplate.execute(status -> {
            KnowledgeDocumentDO current = lockDocument(kbId, docId);
            if (!FILE_FAILED.status().equals(current.getStatus())) {
                throw new ClientException("只有处理失败的文档可以手动重试");
            }
            JsonObject metadata = readMetadata(current);
            metadata.remove("errorMessage");
            metadata.remove("parseStatus");
            updateDocument(current.setStatus(FILE_PENDING.status()), metadata);
            return current;
        });
        if (document == null) {
            throw new ClientException("该文档不存在或出现异常");
        }
        return enqueue(document);
    }

    private KnowledgeDocumentDO requireDocument(Long kbId, Long docId) {
        validateKbId(kbId);
        if (docId == null || docId <= 0) { throw new ClientException("文档ID必须是正整数"); }
        if (baseMapper.selectById(kbId) == null) { throw new ClientException("知识库不存在或已被删除"); }
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (document == null || !Objects.equals(kbId, document.getKbId())) {
            throw new ClientException("文档不存在或已被删除");
        }
        return document;
    }

    @Override
    public void consumeDocument(DocumentMsg msg) {
        // 与删除文档锁同一知识库行；已删除任务直接消费完成，不反复重试。
        KnowledgeDocumentDO document = transactionTemplate.execute(status -> {
            KnowledgeDocumentDO current = findDocumentForConsume(msg.getKbId(), msg.getDocId());
            if (current == null) {
                return null;
            }
            JsonObject metadata = readMetadata(current);
            metadata.remove("errorMessage");
            metadata.remove("parseStatus");
            updateDocument(current.setStatus(FILE_RUNNING.status()), metadata);
            return current;
        });
        if (document == null) {
            log.info("跳过已删除的文档任务，docId={}", msg.getDocId());
            return;
        }
        JsonObject metadata = readMetadata(document);
        try {
            String extension = document.getDocName().substring(document.getDocName().lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);
            ParsedDocument parsed;
            try (InputStream input = storage.open(document.getFilePath())) {
                parsed = parser.parse(input, document.getDocName(), extension);
            }
            String text = parsed.text();
            metadata.addProperty("parseStatus", PARSE_PARSED.status());
            metadata.addProperty("mediaType", parsed.mediaType());
            metadata.addProperty("textLength", text.length());
            // 执行分块
            List<String> chunks = chunkService.toChunks(text);
            // 向量化分块，解析、分块、模型 HTTP 调用都在事务外。
            List<KnowledgeChunkDO> records = chunkService.prepareChunks(chunks, msg.getKbId(), msg.getDocId());
            transactionTemplate.executeWithoutResult(status -> {
                KnowledgeDocumentDO current = findDocumentForConsume(msg.getKbId(), msg.getDocId());
                if (current == null) {
                    return; // 处理期间已删除文档，不再写回片段。
                }
                chunkService.saveChunks(records);
                JsonObject completedMetadata = metadata.deepCopy();
                completedMetadata.addProperty("parseStatus", PARSE_SUCCESS.status());
                completedMetadata.addProperty("chunkCount", records.size());
                // 更新文档状态
                updateDocument(current.setStatus(FILE_SUCCESS.status()), completedMetadata);
            });
        } catch (Exception e) {
            log.error("文档处理失败，docId={}", msg.getDocId(), e);
            if (!metadata.has("parseStatus")) {
                metadata.addProperty("parseStatus", PARSE_FAILED.status());
            }
            // 最终写库事务已经回滚，再用独立短事务登记失败。
            saveFailure(msg, metadata, "文档处理失败，请重试");
            throw new ServiceException("文档处理失败，等待消息队列重试");
        }
    }

    /** MQ 任务在短事务内检查归属；已删除的知识库或文档返回 null。 */
    private KnowledgeDocumentDO findDocumentForConsume(Long kbId, Long docId) {
        validateKbId(kbId);
        if (docId == null || docId <= 0) {
            throw new ClientException("文档ID必须是正整数");
        }
        if (baseMapper.selectActiveForUpdate(kbId) == null) {
            return null;
        }
        KnowledgeDocumentDO document = documentMapper.selectById(docId);
        if (document != null && !Objects.equals(kbId, document.getKbId())) {
            throw new ClientException("文档不属于该知识库");
        }
        return document;
    }

    /**
     * 与删除知识库锁同一行，防止删除后又写入片段。只能在事务中调用。
     */
    private KnowledgeDocumentDO lockDocument(Long kbId, Long docId) {
        if (baseMapper.selectActiveForUpdate(kbId) == null) {
            throw new ClientException("知识库不存在或已被删除");
        }
        return requireDocument(kbId, docId);
    }

    private void saveFailure(DocumentMsg msg, JsonObject metadata, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            // 处理过程中可能删除知识库：已删除的文档不再更新。
            if (baseMapper.selectActiveForUpdate(msg.getKbId()) == null) {
                return;
            }
            KnowledgeDocumentDO current = documentMapper.selectById(msg.getDocId());
            if (current == null || !Objects.equals(msg.getKbId(), current.getKbId())) {
                return;
            }
            metadata.addProperty("chunkCount", 0);
            metadata.addProperty("errorMessage", errorMessage);
            updateDocument(current.setStatus(FILE_FAILED.status()), metadata);
        });
    }

    private void updateDocument(KnowledgeDocumentDO document, JsonObject metadata) {
        document.setMetadata(metadata.toString()).setUpdateTime(null);
        if (documentMapper.updateById(document) != 1) {
            throw new ServiceException("更新文档状态失败");
        }
    }

    private JsonObject readMetadata(KnowledgeDocumentDO document) {
        return document.getMetadata() == null ? new JsonObject()
                : gson.fromJson(document.getMetadata(), JsonObject.class);
    }

    @Override
    public IPage<KnowledgeDocumentRespDTO> listDocument(Long kbId, long current, long size) {
        validateKbId(kbId);
        validatePage(current, size);
        if (baseMapper.selectById(kbId) == null) {
            throw new ClientException("知识库不存在");
        }
        return documentMapper.selectPage(new Page<>(current, size),
                        Wrappers.<KnowledgeDocumentDO>lambdaQuery()
                                .eq(KnowledgeDocumentDO::getKbId, kbId)
                                .orderByDesc(KnowledgeDocumentDO::getId))
                .convert(this::toRespDTO);
    }

    @Override
    public void updateEnabled(Long kbId, Long docId, Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new ClientException("enabled 只能是 0 或 1");
        }
        transactionTemplate.executeWithoutResult(status -> {
            lockDocument(kbId, docId);
            // 只更新开关，避免把处理状态或元数据一起覆盖。
            KnowledgeDocumentDO update = new KnowledgeDocumentDO().setId(docId).setEnabled(enabled);
            if (documentMapper.updateById(update) != 1) {
                throw new ServiceException("修改文档启用状态失败");
            }
        });
    }

    @Override
    public void deleteDocument(Long kbId, Long docId) {
        transactionTemplate.executeWithoutResult(status -> {
            lockDocument(kbId, docId);
            chunkMapper.deletePhysicallyByDocId(docId);
            if (documentMapper.deleteById(docId) != 1) {
                throw new ServiceException("删除文档失败");
            }
        });
    }

    @Override
    public IPage<KnowledgeChunkRespDTO> pageChunks(Long kbId, Long docId, long current, long size) {
        validatePage(current, size);
        requireDocument(kbId, docId);
        return chunkMapper.selectPage(new Page<>(current, size),
                        Wrappers.<KnowledgeChunkDO>lambdaQuery()
                                .select(KnowledgeChunkDO::getId, KnowledgeChunkDO::getKbId,
                                        KnowledgeChunkDO::getDocId, KnowledgeChunkDO::getChunkIndex,
                                        KnowledgeChunkDO::getContent, KnowledgeChunkDO::getCreateTime,
                                        KnowledgeChunkDO::getUpdateTime)
                                .eq(KnowledgeChunkDO::getKbId, kbId)
                                .eq(KnowledgeChunkDO::getDocId, docId)
                                .orderByAsc(KnowledgeChunkDO::getChunkIndex))
                .convert(each -> BeanUtil.copyProperties(each, KnowledgeChunkRespDTO.class));
    }

    private void validatePage(long current, long size) {
        if (current <= 0 || size < 1 || size > 100) {
            throw new ClientException("页码必须大于0，每页数量须为1~100");
        }
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
            throw new ClientException("目前只支持txt、md、doc、docx和带文字的pdf文件");
        }
        return name;
    }

    private KnowledgeDocumentRespDTO toRespDTO(KnowledgeDocumentDO document) {
        KnowledgeDocumentRespDTO response = new KnowledgeDocumentRespDTO().setId(document.getId())
                .setKbId(document.getKbId()).setCreatedBy(document.getCreatedBy())
                .setDocName(document.getDocName()).setStatus(document.getStatus()).setEnabled(document.getEnabled())
                .setCreateTime(document.getCreateTime()).setUpdateTime(document.getUpdateTime());
        if (document.getMetadata() != null) {
            DocumentMetadataDTO metadata = gson.fromJson(document.getMetadata(), DocumentMetadataDTO.class);
            if (metadata != null) {
                response.setParseStatus(metadata.getParseStatus())
                        .setTextLength(metadata.getTextLength())
                        .setChunkCount(metadata.getChunkCount())
                        .setErrorMessage(metadata.getErrorMessage());
            }
        }
        return response;
    }
}
