package com.lhsg.HuiAnswer.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.lhsg.HuiAnswer.rag"
        ,"com.lhsg.HuiAnswer.framework"})
public class HuiAnswerRagApplication {
    public static void main(String[] args) {
        SpringApplication.run(HuiAnswerRagApplication.class, args);
    }
}
