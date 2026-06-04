package org.example.weflow.integration.llm.openai;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = OpenAiStreamingChatModelConfiguration.class,
        properties = "spring.config.location=file:../we-flow-app/src/main/resources/application.yml"
)
class OpenAiStreamingChatModelTest {

    @Autowired
    StreamingChatModel chatModel;

    @Test
    public void chatTest() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        chatModel.chat("hi", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                System.out.print(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }
        });

        completed.await(60, TimeUnit.SECONDS);
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
