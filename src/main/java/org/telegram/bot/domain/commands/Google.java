package org.telegram.bot.domain.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.telegram.bot.domain.CommandParent;
import org.telegram.bot.domain.entities.*;
import org.telegram.bot.domain.enums.ParseModes;
import org.telegram.bot.exception.BotException;
import org.telegram.bot.services.*;
import org.telegram.telegrambots.meta.api.methods.PartialBotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@Component
@AllArgsConstructor
public class Google implements CommandParent<PartialBotApiMethod<?>> {

    private final Logger log = LoggerFactory.getLogger(Google.class);

    private final PropertiesConfig propertiesConfig;
    private final SpeechService speechService;
    private final ImageUrlService imageUrlService;
    private final GoogleSearchResultService googleSearchResultService;
    private final CommandWaitingService commandWaitingService;

    @Override
    public PartialBotApiMethod<?> parse(Update update) throws Exception {
        String token = propertiesConfig.getGoogleToken();
        if (token == null || token.equals("")) {
            throw new BotException(speechService.getRandomMessageByTag("unableToFindToken"));
        }

        Message message = getMessageFromUpdate(update);
        String textMessage;
        String responseText;
        boolean deleteCommandWaiting = false;

        CommandWaiting commandWaiting = commandWaitingService.get(message.getChatId(), message.getFrom().getId());
        if (commandWaiting == null) {
            textMessage = cutCommandInText(message.getText());
        } else {
            textMessage = message.getText();
            deleteCommandWaiting = true;
        }

        if (textMessage == null) {
            deleteCommandWaiting = false;
            log.debug("Empty params. Waiting to continue...");
            commandWaiting = commandWaitingService.get(message.getChatId(), message.getFrom().getId());
            if (commandWaiting == null) {
                commandWaiting = new CommandWaiting();
                commandWaiting.setChatId(message.getChatId());
                commandWaiting.setUserId(message.getFrom().getId());
            }
            commandWaiting.setCommandName("google");
            commandWaiting.setIsFinished(false);
            commandWaiting.setTextMessage("/google ");
            commandWaitingService.save(commandWaiting);

            responseText = "теперь напиши мне что надо найти";
        } else if (textMessage.startsWith("_")) {
            long googleResultSearchId;
            try {
                googleResultSearchId = Long.parseLong(textMessage.substring(1));
            } catch (NumberFormatException e) {
                throw new BotException(speechService.getRandomMessageByTag("wrongInput"));
            }

            GoogleSearchResult googleSearchResult = googleSearchResultService.get(googleResultSearchId);
            if (googleSearchResult == null) {
                throw new BotException(speechService.getRandomMessageByTag("wrongInput"));
            }

            responseText = "<b>" + googleSearchResult.getTitle() + "</b>\n" +
                            googleSearchResult.getSnippet() + "\n" +
                            "<a href='" + googleSearchResult.getLink() + "'>" + googleSearchResult.getFormattedUrl() + "</a>\n";

            InputStream image;
            ImageUrl imageUrl = googleSearchResult.getImageUrl();
            if (imageUrl != null) {
                try {
                    SendPhoto sendPhoto = new SendPhoto();
                    sendPhoto.setPhoto(new InputFile(imageUrl.getUrl()));
                    sendPhoto.setCaption(responseText);
                    sendPhoto.setParseMode(ParseModes.HTML.getValue());
                    sendPhoto.setReplyToMessageId(message.getMessageId());
                    sendPhoto.setChatId(message.getChatId().toString());

                    return sendPhoto;

                } catch (Exception ignored) {
                }
            }
        } else {

            GoogleSearchData googleSearchData = getResultOfSearch(textMessage, token);

            if (googleSearchData.getItems() == null) {
                throw new BotException("Ничего не нашёл по такому запросу");
            }

            List<GoogleSearchResult> googleSearchResults = googleSearchData.getItems()
                    .stream()
                    .map(googleSearchItem -> {
                        ImageUrl imageUrl = null;

                        Pagemap pagemap = googleSearchItem.getPagemap();
                        if (pagemap != null) {
                            List<Src> srcList = pagemap.getCseImage();
                            if (srcList != null && !srcList.isEmpty()) {
                                imageUrl = new ImageUrl();
                                imageUrl.setTitle(googleSearchItem.getTitle());
                                imageUrl.setUrl(srcList.get(0).getSrc());
                                imageUrl = imageUrlService.save(imageUrl);
                            }
                        }

                        GoogleSearchResult googleSearchResult = new GoogleSearchResult();
                        googleSearchResult.setTitle(googleSearchItem.getTitle());
                        googleSearchResult.setLink(googleSearchItem.getLink());
                        googleSearchResult.setDisplayLink(googleSearchItem.getDisplayLink());
                        googleSearchResult.setSnippet(googleSearchItem.getSnippet());
                        googleSearchResult.setFormattedUrl(googleSearchItem.getFormattedUrl());
                        googleSearchResult.setImageUrl(imageUrl);

                        return googleSearchResult;
                    })
                    .collect(Collectors.toList());

            StringBuilder buf = new StringBuilder();
            googleSearchResultService.save(googleSearchResults).forEach(googleSearchResult ->
                    buf.append(googleSearchResult.getTitle()).append("\n")
                            .append(googleSearchResult.getDisplayLink()).append("\n")
                            .append("/google_").append(googleSearchResult.getId()).append("\n\n")
            );

            SearchInformation searchInformation = googleSearchData.getSearchInformation();
            buf.append("Результатов: примерно ").append(searchInformation.getFormattedTotalResults()).append(" (").append(searchInformation.getFormattedSearchTime()).append(" сек.) ");

            responseText = buf.toString();
        }

        if (deleteCommandWaiting) {
            commandWaitingService.remove(commandWaiting);
        }

        SendMessage sendMessage = new SendMessage();
        sendMessage.setReplyToMessageId(message.getMessageId());
        sendMessage.setChatId(message.getChatId().toString());
        sendMessage.setParseMode(ParseModes.HTML.getValue());
        sendMessage.disableWebPagePreview();
        sendMessage.setText(responseText);

        return sendMessage;
    }

    private GoogleSearchData getResultOfSearch(String requestText, String googleToken) {
        RestTemplate restTemplate = new RestTemplate();
        String GOOGLE_URL = "https://www.googleapis.com/customsearch/v1?";
        ResponseEntity<GoogleSearchData> response = restTemplate.getForEntity(
                GOOGLE_URL + "key=" + googleToken + "&q=" + requestText, GoogleSearchData.class);

        return response.getBody();
    }

    @Data
    private static class GoogleSearchData {
        @JsonIgnore
        private String kind;
        @JsonIgnore
        private String url;
        @JsonIgnore
        private String queries;
        @JsonIgnore
        private String context;

        private SearchInformation searchInformation;

        private List<GoogleSearchItem> items;
    }

    @Data
    private static class SearchInformation {
        private Float searchTime;
        private String formattedSearchTime;
        private String totalResults;
        private String formattedTotalResults;
    }

    @Data
    private static class GoogleSearchItem {
        private String kind;
        private String title;
        private String htmlTitle;
        private String link;
        private String displayLink;
        private String snippet;
        private String htmlSnippet;
        private String cacheId;
        private String formattedUrl;
        private String htmlFormattedUrl;
        private Pagemap pagemap;
    }

    @Data
    private static class Pagemap {
        @JsonIgnore
        @JsonProperty("cse_thumbnail")
        private List<Object> cseThumbnail;

        @JsonIgnore
        private List<Object> metatags;

        @JsonProperty("cse_image")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<Src> cseImage;
    }

    @Data
    private static class Src {
        private String src;
    }
}
