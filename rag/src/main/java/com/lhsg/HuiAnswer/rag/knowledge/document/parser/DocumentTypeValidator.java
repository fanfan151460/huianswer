package com.lhsg.HuiAnswer.rag.knowledge.document.parser;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 上传前检查实际文件类型与扩展名是否匹配。 */
@Component
public class DocumentTypeValidator {
    private final DefaultDetector detector = new DefaultDetector();

    public String validate(MultipartFile file, String extension) {
        MediaType type;
        // 不传文件名或浏览器声明的 Content-Type，让检测以文件内容为依据。
        // TikaInputStream 支持检查 ZIP/OLE 容器内部，避免把任意 ZIP 都当作 DOCX。
        try (TikaInputStream input = TikaInputStream.get(file.getInputStream())) {
            type = detector.detect(input, new Metadata()).getBaseType();
        } catch (Exception e) {
            throw new ClientException("无法识别文件类型，请检查文件内容");
        }
        boolean matches = switch (extension) {
            case "txt", "md" -> MediaType.TEXT_PLAIN.equals(type);
            case "pdf" -> "application/pdf".equals(type.toString());
            case "doc" -> "application/msword".equals(type.toString());
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    .equals(type.toString());
            default -> false;
        };
        if (!matches) {
            throw new ClientException("文件内容与扩展名不匹配，请上传正确格式的文件");
        }
        return type.toString();
    }
}
