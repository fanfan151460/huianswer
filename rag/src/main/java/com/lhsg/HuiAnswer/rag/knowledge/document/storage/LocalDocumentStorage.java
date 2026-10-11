package com.lhsg.HuiAnswer.rag.knowledge.document.storage;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 原文件存储：展示名与磁盘文件名分开，同名上传不会覆盖。 */
@Component
public class LocalDocumentStorage {
    private final Path root;

    public LocalDocumentStorage(@Value("${rag.storage.local-root}") String localRoot) {
        root = Path.of(localRoot).toAbsolutePath().normalize();
    }

    /**
     * 保存文件到本地
     * @param kbId 知识库id作为目录
     * @param file 接受的文件
     * @param extension 扩展名
     * @return 文件path
     */
    public String save(Long kbId, MultipartFile file, String extension) {
        Path directory = root.resolve(kbId.toString());
        Path target = directory.resolve(UUID.randomUUID() + "." + extension).normalize();
        if (!target.startsWith(root)) {
            throw new ServiceException("文件保存路径不合法");
        }
        try {
            Files.createDirectories(directory);
            // CREATE_NEW 禁止覆盖已有文件；不使用用户传入的文件名拼接路径。
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                input.transferTo(output);
            }
            // 返回写入文件的地址
            return target.toString();
        } catch (IOException e) {
            throw new ServiceException("保存原文件失败，请检查存储目录");
        }
    }

    public InputStream open(String filePath) throws IOException {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IOException("文件不在文档存储目录中");
        }
        return Files.newInputStream(path);
    }
}
