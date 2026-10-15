package com.lhsg.HuiAnswer.rag.retrieval.process;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.KnowledgeCatalogDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.Identify;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Component
@RequiredArgsConstructor
@Slf4j
public class SubQuestionProcessor {
    private final Identify identify;
    private final SearchChunks searchChunks;

    @Resource(name = "subQuestionExecutor")
    private ThreadPoolExecutor subQuestionExecutor;

    /** 先提交全部完整任务，再逐个等待结果；任务执行并行，结果保持输入顺序。 */
    public List<SubQuestionResultDTO> process(List<String> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        List<KnowledgeCatalogDTO> catalog = identify.loadCatalog();
        List<Future<SubQuestionResultDTO>> futures = new ArrayList<>();
        boolean completed = false;
        try {
            for (int i = 0; i < questions.size(); i++) {
                int index = i;
                String question = questions.get(i);
                futures.add(subQuestionExecutor.submit(() -> processOne(question, index, catalog)));
            }
            List<SubQuestionResultDTO> results = new ArrayList<>();
            for (Future<SubQuestionResultDTO> future : futures) {
                results.add(future.get());
            }
            completed = true;
            return results;
        } catch (RejectedExecutionException e) {
            throw new ServiceException("子问题处理繁忙，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("子问题处理已中断");
        } catch (ExecutionException e) {
            // 本地配置等错误正常抛出；模型路由失败由 Identify 自己降级。
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ServiceException("子问题处理失败");
        } finally {
            if (!completed) {
                for (Future<SubQuestionResultDTO> future : futures) {
                    future.cancel(true);
                }
            }
        }
    }

    /** 一个任务内：先路由，再执行对应分支。 */
    private SubQuestionResultDTO processOne(String question, int index, List<KnowledgeCatalogDTO> catalog) {
        checkInterrupted();
        IntentResultDTO route = identify.identifyOne(question, index, catalog);
        checkInterrupted();
        if (route.getIntent() == IntentEnum.CHAT) {
            return processChat(question, route);
        }
        if (route.getIntent() == IntentEnum.CLARIFY) {
            return processClarify(question, route);
        }
        return processSearch(question, route);
    }

    /** 普通聊天的回复由最终模型生成，这里只保留处理要求。 */
    private SubQuestionResultDTO processChat(String question, IntentResultDTO route) {
        return result(question, route);
    }

    /** 澄清文案由最终模型结合历史生成，这里不查询片段。 */
    private SubQuestionResultDTO processClarify(String question, IntentResultDTO route) {
        return result(question, route);
    }

    private SubQuestionResultDTO processSearch(String question, IntentResultDTO route) {
        SubQuestionResultDTO result = result(question, route);
        try {
            List<Long> kbIds = route.getIntent() == IntentEnum.NO_MATCH ? null : route.getKbIds();
            List<Reference> references = searchChunks.mixSearch(kbIds, question);
            checkInterrupted();
            // 只返回本子问题的 RRF 候选；全部任务结束后统一重排序。
            return result.setReferences(references);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            log.warn("子问题 {} 检索失败，异常类型：{}", route.getIndex(), e.getClass().getSimpleName());
            return result.setErrorMessage("该子问题的资料检索暂时失败，请稍后再试");
        }
    }

    private SubQuestionResultDTO result(String question, IntentResultDTO route) {
        return new SubQuestionResultDTO().setIndex(route.getIndex())
                .setQuestion(question).setIntent(route.getIntent());
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("子问题处理已取消");
        }
    }
}
