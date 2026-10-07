package com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil;

import com.google.gson.*;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 调用硅基流动的 Embedding 接口；只负责文本到向量，不负责写数据库。 */
@Component
@RequiredArgsConstructor
public class EmbeddingChunks {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.ai.siliconflow.url}")
    private String url;
    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.ai.siliconflow.embedding-model}")
    private String embeddingModel;

    private int embeddingBatchSize = 32;
    private int embeddingTimeoutSeconds = 60;

    @Value("${rag.vector.dimension:4096}")
    private int vectorDimension = 4096;

    private final Gson gson;
    // 复用连接；总耗时由每次 Call 的 timeout 控制，不自动重试或跟随重定向。
    private final OkHttpClient httpClient;

    /** 分批次处理分块 */
    public List<float[]> doEmbedding(List<String> chunks) {
        // 1. 将长列表分成小批，每一批都包含独立的 input 列表。
        List<float[]> result = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size();) {
            int end = Math.min(start + embeddingBatchSize, chunks.size());
            List<String> batch = chunks.subList(start, end);
            result.addAll(requestBatch(batch));
            start = end;
        }
        return result;
    }

    private List<float[]> requestBatch(List<String> batch) {
        // 2. 组装请求 JSON，dimensions 与数据库 vector 列保持一致。
        JsonObject body = new JsonObject();
        JsonArray data = new JsonArray();
        for (String text : batch) {
            data.add(text);
        }

        // 构建请求体
        body.addProperty("model", embeddingModel);
        body.add("input", data);
        body.addProperty("encoding_format", "float");
        body.addProperty("dimensions", vectorDimension);
        Request request;
        try {
            request = new Request.Builder()
                    .url(url + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey.strip())
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new ServiceException("向量请求配置不正确，请检查 API key 格式");
        }
        // 覆盖本次请求的完整耗时
        Call call = httpClient.newCall(request);
        call.timeout().timeout(embeddingTimeoutSeconds, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String message = switch (response.code()) {
                    case 401, 403 -> "向量接口鉴权失败，请检查 API key 和模型权限";
                    case 429 -> "向量接口限流，请稍后重试";
                    default -> "调用向量接口失败（HTTP " + response.code() + "）";
                };
                throw new ServiceException(message);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new ServiceException("向量接口返回空响应");
            }
            return parseResponse(responseBody.string(), batch.size());
        } catch (ServiceException e) {
            throw new ServiceException(e.getMessage());
        } catch (Exception e) {
            throw new ServiceException("向量化服务请求异常");
        }
    }

    private List<float[]> parseResponse(String body, int expectedSize) {
        // 3. fromJson 按字段名把 JSON 填进对象，embedding 直接转换成 float[]。
        try {
            EmbeddingResponse response = gson.fromJson(body, EmbeddingResponse.class);
            List<float[]> result = new ArrayList<>(Collections.nCopies(expectedSize, null));
            for (EmbeddingData item : response.data) {
                if (item == null || item.index == null) {
                    throw new ServiceException("向量响应缺少有效的 index");
                }
                int position = item.index;
                result.set(position, item.embedding);
            }
            return result;
        } catch (JsonParseException e) {
            throw new ServiceException("向量接口返回的 JSON 格式不正确");
        }
    }

    /** 响应外层：{ "data": [...], "error": ... }，其它字段不需要使用。 */
    private static class EmbeddingResponse {
        private List<EmbeddingData> data;
        private JsonElement error;
    }

    /** data 中的一项；字段名对应 API 返回的 index 与 embedding。 */
    private static class EmbeddingData {
        // 用 Integer 保留缺失时的 null，不能把缺失的 index 当成第 0 项。
        private Integer index;
        private float[] embedding;
    }
}
