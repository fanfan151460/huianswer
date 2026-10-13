package com.lhsg.HuiAnswer.rag.retrieval.rerank;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RerankReqDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RerankRespDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RerankResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RerankChunks {
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ApiUrl apiUrl;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.ai.siliconflow.rerank-model}")
    private String rerankModel;
    @Value("${rag.rerank.timeout-seconds}")
    private int timeoutSeconds;
    @Value("${rag.rerank.top-n}")
    private int topN;

    /** 合并候选交给模型重排；请求失败时按候选原顺序保留前 topN 个片段。 */
    @TimeAnnotation(method = "重编排")
    public List<Reference> rerank(String question, List<Reference> candidates) {
        if (topN <= 0) {
            throw new IllegalArgumentException("topN 必须大于 0");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(topN, candidates.size());
        try {
            if (apiKey == null || apiKey.isBlank() || timeoutSeconds <= 0) {
                throw new IllegalStateException("重排序配置不完整");
            }
            Request request = getRequest(question, candidates);
            Call call = httpClient.newCall(request);
            // 只限制这次重排序调用的总耗时，不改聊天客户端的流式读取设置。
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    throw new IOException("重排序接口返回 HTTP " + response.code());
                }
                RerankRespDTO resp = gson.fromJson(body.string(), RerankRespDTO.class);
                return mapResults(resp, candidates);
            }
        } catch (Exception e) {
            // 不打印请求头、完整请求或响应，避免把密钥和文档内容带进日志。
            log.warn("重排序失败，按候选顺序保留前 {} 个片段，异常类型：{}", limit, e.getClass().getSimpleName());
            return new ArrayList<>(candidates.subList(0, limit));
        }
    }

    private Request getRequest(String question, List<Reference> candidates) {
        RerankReqDTO req = new RerankReqDTO()
                .setModel(rerankModel)
                .setQuery(question)
                .setDocuments(candidates.stream().map(Reference::getContent).toList())
                .setTopN(Math.min(topN, candidates.size()));
        String requestJson = gson.toJson(req);
        return new Request.Builder()
                .url(apiUrl.rerankUrl())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson, JSON))
                .build();
    }

    private List<Reference> mapResults(RerankRespDTO resp, List<Reference> candidates) {
        if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) {
            throw new IllegalStateException("重排序未返回结果");
        }
        List<Reference> reordered = new ArrayList<>();
        Set<Integer> usedIndexes = new HashSet<>();
        for (RerankResultDTO result : resp.getResults()) {
            Integer index = result == null ? null : result.getIndex();
            if (index == null || index < 0 || index >= candidates.size() || !usedIndexes.add(index)) {
                throw new IllegalStateException("重排序返回了无效的片段下标");
            }
            reordered.add(candidates.get(index).setRerankScore(result.getRelevanceScore()));
            if (reordered.size() == Math.min(topN, candidates.size())) {
                break;
            }
        }
        return reordered;
    }
}
