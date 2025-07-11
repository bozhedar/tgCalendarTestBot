package tg.bot.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tg.bot.service.yandex.YandexService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class UpdateConsumer implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final YandexService yandexService;
    @Value("${contact.owner}")
    private String ownerUrl;

    public UpdateConsumer(@Value("${bot.token}") String token, YandexService yandexService) {

        this.telegramClient = new OkHttpTelegramClient(token);

        this.yandexService = yandexService;
    }

    @Override
    public void consume(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            Long chatId = update.getMessage().getChatId();
            Integer messageId = update.getMessage().getMessageId();
            String message = update.getMessage().getText();
            log.info("message received.");

            if (message.equals("/start")) {
                sendMainMenu(chatId);
                deleteMessage(chatId, messageId);
            } else deleteMessage(chatId, messageId);
        }

        else if (update.hasCallbackQuery()) {
            handleCallBackQuery(update.getCallbackQuery());

        }
    }

    private void handleCallBackQuery(CallbackQuery callbackQuery) {
        var chatId = callbackQuery.getFrom().getId();
        var data = callbackQuery.getData();
        var messageId = callbackQuery.getMessage().getMessageId();
        switch (data) {
            case "reload" -> reload(chatId, messageId);
        }
    }

    private void reload(Long chatId, Integer messageId) {
        sendMainMenu(chatId);
        deleteMessage(chatId, messageId);
    }

    @SneakyThrows
    public void sendMainMenu(Long chatId) {
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(yandexService.printSlots()

                )
                .build();

        InlineKeyboardButton buttonReload = InlineKeyboardButton.builder()
                .text("Перезагрузить")
                .callbackData("reload")
                .build();

        InlineKeyboardButton buttonWrite = InlineKeyboardButton.builder()
                .text("Записаться")
                .callbackData("write")
                .url(ownerUrl)
                .build();

        List<InlineKeyboardRow> keyboardRows = List.of(
                new InlineKeyboardRow(buttonReload),
                new InlineKeyboardRow(buttonWrite));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboardRows);

        message.setReplyMarkup(markup);

        telegramClient.execute(message);
    }

    @SneakyThrows
    public void deleteMessage(Long chatId, Integer messageId) {
        DeleteMessage message = DeleteMessage.builder()
                .messageId(messageId)
                .chatId(chatId)
                .build();
        telegramClient.execute(message);
    }
}
