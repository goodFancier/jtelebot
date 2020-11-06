package org.telegram.bot.domain.commands;

import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.bot.domain.CommandParent;
import org.telegram.bot.domain.entities.CommandWaiting;
import org.telegram.bot.domain.entities.User;
import org.telegram.bot.domain.enums.ParseModes;
import org.telegram.bot.exception.BotException;
import org.telegram.bot.services.CommandWaitingService;
import org.telegram.bot.services.SpeechService;
import org.telegram.bot.services.UserService;
import org.telegram.bot.services.UserStatsService;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.telegram.bot.utils.DateUtils.deltaDatesToString;

@Component
@AllArgsConstructor
public class Where implements CommandParent<SendMessage> {

    private final Logger log = LoggerFactory.getLogger(Where.class);

    private final SpeechService speechService;
    private final UserService userService;
    private final UserStatsService userStatsService;
    private final CommandWaitingService commandWaitingService;

    @Override
    public SendMessage parse(Message message) throws Exception {
        CommandWaiting commandWaiting = commandWaitingService.get(message.getChatId(), message.getFrom().getId());
        String textMessage;
        if (commandWaiting == null) {
            textMessage = cutCommandInText(message.getText());
        } else {
            textMessage = message.getText();
        }

        String responseText;
        if (textMessage == null) {
            log.debug("Empty params. Waiting to continue...");
            commandWaiting = commandWaitingService.get(message.getChatId(), message.getFrom().getId());
            if (commandWaiting == null) {
               commandWaiting = new CommandWaiting();
               commandWaiting.setChatId(message.getChatId());
               commandWaiting.setUserId(message.getFrom().getId());
            }
            commandWaiting.setCommandName("where");
            commandWaiting.setIsFinished(false);
            commandWaiting.setTextMessage("/where ");
            commandWaitingService.save(commandWaiting);

            responseText = "теперь напиши мне username того, кого хочешь найти";
        } else {
            User user = userService.get(textMessage);
            if (user == null) {
                throw new BotException(speechService.getRandomMessageByTag("userNotFount"));
            }

            LocalDateTime dateOfMessage = userStatsService.get(message.getChatId(), user.getUserId()).getLastMessage().getDate();
            ZoneId zoneId = ZoneId.systemDefault();

            responseText = "последний раз пользователя *" + user.getUsername() +
                    "* я видел " + dateOfMessage + " (" + zoneId.getId() + ").\n" +
                    "Молчит уже " + deltaDatesToString(LocalDateTime.now(), dateOfMessage);
        }

        if (commandWaiting != null) {
            commandWaitingService.remove(commandWaiting);
        }

        return new SendMessage()
                .setChatId(message.getChatId())
                .setReplyToMessageId(message.getMessageId())
                .setParseMode(ParseModes.MARKDOWN.getValue())
                .setText(responseText);
        }
}
