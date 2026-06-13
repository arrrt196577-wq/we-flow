package org.example.weflow.integration.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
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
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();

        chatModel.chat("请简要回答：9.11 和 9.9 哪个数字更大？", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                answer.append(partialResponse);
                System.out.print(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                reasoning.append(partialThinking.text());
                System.out.print(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                AiMessage aiMessage = response.aiMessage();
                if (reasoning.isEmpty() && aiMessage != null && hasText(aiMessage.thinking())) {
                    reasoning.append(aiMessage.thinking());
                }
                completed.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                error.set(throwable);
                completed.countDown();
            }
        });

        assertThat(completed.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(answer.toString()).isNotBlank();
        assertThat(reasoning.toString())
                .as("configured model/provider did not return reasoning_content")
                .isNotBlank();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
