package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.duplicate.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回归：上传文件的 Resource 不能直接 JSON 序列化，内容相同/不同仍应正确判重。 */
class MultipartDuplicateSubmitTest {
    @NoDuplicateSubmit public void target() {}
    @Test void shouldHashMultipartContentWithoutSerializingItsResource() throws Throwable {
        var redis=mock(StringRedisTemplate.class); var ops=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(),anyString(),anyLong(),any(TimeUnit.class))).thenReturn(true);
        var aspect=new NoDuplicateAspect(redis); var point=mock(ProceedingJoinPoint.class);
        var annotation=getClass().getMethod("target").getAnnotation(NoDuplicateSubmit.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            for(String content:new String[]{"same","same","different"}) {
                when(point.getArgs()).thenReturn(new Object[]{7L,new MockMultipartFile("file","a.txt","text/plain",content.getBytes())});
                aspect.noDuplicate(point,annotation);
            }
            var keys=ArgumentCaptor.forClass(String.class);
            verify(ops,times(3)).setIfAbsent(keys.capture(),anyString(),anyLong(),any(TimeUnit.class));
            assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));
            assertNotEquals(keys.getAllValues().get(0),keys.getAllValues().get(2));
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
