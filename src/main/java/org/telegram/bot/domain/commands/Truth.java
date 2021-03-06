package org.telegram.bot.domain.commands;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.bot.domain.CommandParent;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.telegram.bot.utils.MathUtils.getRandomInRange;

@Component
@AllArgsConstructor
public class Truth implements CommandParent<SendMessage> {

    @Override
    public SendMessage parse(Update update) throws Exception {
        Message message = getMessageFromUpdate(update);

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(message.getChatId().toString());
        sendMessage.setReplyToMessageId(message.getMessageId());
        sendMessage.enableMarkdown(true);
        sendMessage.setText(buildResponseMessage());

        return sendMessage;
    }

    private String buildResponseMessage() {
        int prob = getRandomInRange(0, 100);
        String comment;

        if (prob == 0) {
            comment = "пиздит";
        } else if (prob > 0 && prob < 21) {
            comment = "врёт";
        } else if (prob > 20 && prob < 41) {
            comment = "привирает";
        } else if (prob > 40 && prob < 61) {
            comment = "ну чот хз";
        } else if (prob > 60 && prob < 81) {
            comment = "похоже на правду";
        } else if (prob > 80 && prob < 100) {
            comment = "дело говорит";
        } else {
            comment = "не пиздит";
        }

        return "Вероятность того, что выражение верно - *" + prob + "%*\n(" + comment + ")";
    }
}
