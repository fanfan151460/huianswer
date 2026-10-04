package com.lhsg.HuiAnswer.rag.knowledge.service.impl;

import cn.hutool.core.util.StrUtil;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements IKnowledgeChunkService {

    @Value("${rag.chunk.chunk-size}")
    private int CHUNK_SIZE;
    @Value("${rag.chunk.OVERLAP}")
    private int OVERLAP;
    @Value("${rag.vector.dimension:4096}")
    private int vectorDimension = 4096;

    private final EmbeddingChunks embeddingChunks;
    private final KnowledgeChunkMapper chunkMapper;

    @PostConstruct
    private void validateConfiguration() {
        if (CHUNK_SIZE <= 0 || OVERLAP < 0 || OVERLAP >= CHUNK_SIZE) {
            throw new IllegalArgumentException("分块大小必须大于0，重叠必须满足0 <= overlap < chunkSize");
        }
        if (vectorDimension <= 0) {
            throw new IllegalArgumentException("向量维度必须大于0");
        }
    }

    @Override
    public List<String> toChunks(String text) {
        validateConfiguration();
        if (StrUtil.isBlank(text)) {
            throw new ClientException("文档内容为空");
        }
        // 按 Unicode 字符计数，避免 substring 把 emoji 的 UTF-16 代理对切开。
        int[] characters = text.codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < characters.length) {
            int end = Math.min(start + CHUNK_SIZE, characters.length);
            String content = new String(characters, start, end - start);
            if (!content.isBlank()) {
                chunks.add(content);
            }
            // 到达全文末尾即结束；最后不足一块也保留，不再单独生成重叠尾块。
            if (end == characters.length) {
                break;
            }
            start = end - OVERLAP;
        }
        return chunks;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveChunks(List<String> chunks, Long kbId, Long docId) {
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(StrUtil::isBlank)
                || kbId == null || kbId <= 0 || docId == null || docId <= 0) {
            throw new ClientException("片段内容和文档归属不能为空或不合法");
        }
        List<float[]> embeddingList = embeddingChunks.doEmbedding(chunks);
        if (embeddingList == null || embeddingList.size() != chunks.size()) {
            throw new ServiceException("返回的向量数量与片段数量不一致");
        }
        // 全部校验通过再开始写库，不能把错位或错误维度的向量写进去。
        for (float[] vector : embeddingList) {
            if (vector == null || vector.length != vectorDimension) {
                throw new ServiceException("返回的向量维度与数据库配置不一致");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new ServiceException("返回的向量含有非法数值");
                }
            }
        }
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunkDO chunk = new KnowledgeChunkDO().setChunkIndex(i)
                    .setContent(chunks.get(i)).setEmbedding(embeddingList.get(i))
                    .setKbId(kbId).setDocId(docId);
            // 普通 insert 使用当前 Spring 事务；任意一条失败，整次调用回滚。
            if (chunkMapper.insert(chunk) != 1) {
                throw new ServiceException("保存片段失败");
            }
        }
    }
}