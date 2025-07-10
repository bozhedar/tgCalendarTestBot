package tg.bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;


@Component
public class TgBot implements SpringLongPollingBot {
    private final UpdateConsumer updateConsumer;
    private final String token;

    public TgBot(UpdateConsumer updateConsumer, @Value("${bot.token}") String token) {
        this.updateConsumer = updateConsumer;
        this.token = token;



    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updateConsumer;
    }

}
