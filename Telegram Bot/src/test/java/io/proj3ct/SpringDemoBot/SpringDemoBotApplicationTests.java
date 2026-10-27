package io.proj3ct.SpringDemoBot;

import io.proj3ct.SpringDemoBot.service.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class SpringDemoBotApplicationTests {

    @MockBean
    private TelegramBot telegramBot;

    @Test
    void contextLoads() {
    }
}
