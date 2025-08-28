package io.proj3ct.SpringDemoBot.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.tools.javac.Main;
import com.vdurmont.emoji.EmojiParser;
import io.proj3ct.SpringDemoBot.config.BotConfig;
import io.proj3ct.SpringDemoBot.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BloggerRepository bloggerRepository;
    @Autowired
    private VideoRepository videoRepository;

    final BotConfig config;

    static final String ERROR_TEXT = "Error occurred: ";

    private final Sheets sheetsService;
    private static final String SPREADSHEET_ID = "..."; 
    private static final String SHEET_NAME = "...";
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json"; 


    public TelegramBot(BotConfig config) throws IOException, GeneralSecurityException {
        this.config = config;
        List<BotCommand> listofCommands = new ArrayList<>();
        listofCommands.add(new BotCommand("/start", "get a welcome message"));
        listofCommands.add(new BotCommand("/addAdmin", "add a new admin (owner only)")); 
        try {
            this.execute(new SetMyCommands(listofCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Error setting bot's command list: " + e.getMessage());
        }

        // Инициализация Google Sheets API
        GoogleCredentials credentials;
        try (InputStream in = TelegramBot.class.getResourceAsStream(CREDENTIALS_FILE_PATH)) {
            credentials = GoogleCredentials.fromStream(in).createScoped(Arrays.asList(SheetsScopes.SPREADSHEETS));
        }
        this.sheetsService = new Sheets.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), new HttpCredentialsAdapter(credentials)).setApplicationName("Telegram Bot").build();
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            

            if (userRepository.findById(update.getMessage().getChatId()).isEmpty()) {
                registerUser(update.getMessage());
            }
            // Check if user is admin or owner
            if (!isAdminOrOwner(chatId)) {
                sendMessage(chatId, "У вас нет прав.");
                return;
            }

            if (messageText.contains("/send") && chatId == config.getOwnerId()) {
                var textToSend = EmojiParser.parseToUnicode(messageText.substring(messageText.indexOf(" ")));
                var users = userRepository.findAll();
                for (User user : users) {
                    prepareAndSendMessage(user.getChatId(), textToSend);
                }
            } else if (messageText.startsWith("/addAdmin") && chatId == config.getOwnerId()) {
                handleAddAdminCommand(chatId, messageText);
            } else if (messageText.equals("/sync")) {
                syncMySQLToGoogleSheets(chatId);
            } else {
                String state = getUserState(chatId);
                if (state != null && !messageText.equals("/start")) {
                    registerUser(update.getMessage());

                    handleState(chatId, state, messageText);
                } else {
                    switch (messageText) {
                        case "/start":
                            registerUser(update.getMessage());
                            startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                            break;
                        default:
                            sendMessage(chatId, "Неизвестная команда.");
                    }
                }
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long messageId = update.getCallbackQuery().getMessage().getMessageId();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            // Check if user is admin or owner
            if (!isAdminOrOwner(chatId)) {
                sendMessage(chatId, "У вас нет прав.");
                return;
            }

            if (callbackData.equals("ADD_BLOGGER")) {
                setUserState(chatId, "ADDING_BLOGGER_NICKNAME");
                sendMessage(chatId, "Пожалуйста, укажите никнейм блогера.");
            } else if (callbackData.equals("INTERACT_BLOGGER")) {
                setUserState(chatId, "WAITING_FOR_BLOGGER_ID");
                sendMessage(chatId, "Пожалуйста, укажите ID блогера.");
            } else if (callbackData.startsWith("ADD_VIDEO_")) {
                long bloggerId = Long.parseLong(callbackData.substring("ADD_VIDEO_".length()));
                Blogger blogger = bloggerRepository.findById(bloggerId).orElse(null);
                if (blogger != null && blogger.getPoints() > 0) {
                    setUserState(chatId, "ADDING_VIDEO_FOR_BLOGGER_" + bloggerId + "_MESSAGE_" + messageId);
                    sendMessage(chatId, "Пожалуйста, укажите ссылку на видео.");
                } else {
                    String text = "Нельзя добавить видео: поинтов недостаточно или блогер не найден.";
                    executeEditMessageText(text, chatId, messageId);
                }

            } else if (callbackData.startsWith("ADD_POINTS_")) {
                long bloggerId = Long.parseLong(callbackData.substring("ADD_POINTS_".length()));
                setUserState(chatId, "ADDING_POINTS_TO_BLOGGER_" + bloggerId);
                sendMessage(chatId, "Пожалуйста, укажите количество поинтов для добавления:");
                executeEditMessageText("Ожидание ввода количества поинтов...", chatId, messageId);
            } else if (callbackData.equals("BACK")) {
                setUserState(chatId, null);
                listBloggers(chatId, 1);
            } else if (callbackData.startsWith("NEXT_PAGE_")) {
                int page = Integer.parseInt(callbackData.substring("NEXT_PAGE_".length()));
                setUserState(chatId, "LIST_BLOGGERS_PAGE_" + page);
                listBloggers(chatId, page);
            } else if (callbackData.startsWith("PREV_PAGE_")) {
                int page = Integer.parseInt(callbackData.substring("PREV_PAGE_".length()));
                setUserState(chatId, "LIST_BLOGGERS_PAGE_" + page);
                listBloggers(chatId, page);
            }
        }
    }

    // Новая запланированная задача для отправки уведомлений каждую пятницу
    @Scheduled(cron = "0 00 10 * * FRI") // Запуск каждую пятницу в 10:00
    private void sendLowPointsNotification() {
        // Найти блогеров с менее чем 2 поинтами
        List<Blogger> lowPointsBloggers = bloggerRepository.findByPointsLessThan(2);
        StringBuilder message = new StringBuilder();

        if (lowPointsBloggers.isEmpty()) {
            message.append("У всех блогеров достаточно поинтов (>= 2).");
        } else {
            message.append("Блогеры с менее чем 2 поинтами:\n\n");
            for (Blogger blogger : lowPointsBloggers) {
                message.append("ID: ").append(blogger.getId()).append(", Никнейм: ").append(blogger.getNickname()).append(", Поинты: ").append(blogger.getPoints()).append("\n");
            }
        }

        // Найти всех администраторов и владельца
        List<User> admins = userRepository.findByIsAdminTrue();
        List<Long> adminChatIds = new ArrayList<>();

        adminChatIds.add(config.getOwnerId()); // Добавить владельца
        for (User admin : admins) {
            if (!admin.getChatId().equals(config.getOwnerId())) { // Избежать дублирования владельца
                adminChatIds.add(admin.getChatId());
            }
        }

        // Отправить уведомление каждому администратору
        for (Long chatId : adminChatIds) {
            prepareAndSendMessage(chatId, message.toString());
        }
    }

    private void handleAddAdminCommand(long chatId, String messageText) {
        if (chatId != config.getOwnerId()) {
            sendMessage(chatId, "Только владелец может добавлять администраторов.");
            return;
        }

        String[] parts = messageText.split(" ");
        if (parts.length < 2) {
            sendMessage(chatId, "Пожалуйста, укажите chatId пользователя. Пример: /addAdmin 123456789");
            return;
        }

        try {
            long newAdminId = Long.parseLong(parts[1]);
            User user = userRepository.findById(newAdminId).orElse(null);
            if (user != null) {
                user.setAdmin(true);
                userRepository.save(user);
                sendMessage(chatId, "Пользователь с chatId " + newAdminId + " теперь администратор.");
            } else {
                sendMessage(chatId, "Пользователь с chatId " + newAdminId + " не найден.");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Неверный формат chatId.");
        }
    }

    private boolean isAdminOrOwner(long chatId) {
        if (chatId == config.getOwnerId()) {
            return true;
        }
        User user = userRepository.findById(chatId).orElse(null);
        return user != null && user.isAdmin();
    }

    private void handleState(long chatId, String state, String messageText) {
        if (state.equals("ADDING_BLOGGER_NICKNAME")) {
            User user = userRepository.findById(chatId).get();
            user.setTempData(messageText);
            user.setState("ADDING_BLOGGER_LINK");
            userRepository.save(user);
            sendMessage(chatId, "Пожалуйста, укажите ссылку на канал блогера.");
        } else if (state.equals("ADDING_BLOGGER_LINK")) {
            User user = userRepository.findById(chatId).get();
            String nickname = user.getTempData();
            user.setTempData(nickname + "|" + messageText);
            user.setState("ADDING_BLOGGER_TAGS");
            userRepository.save(user);

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(String.valueOf(chatId));
            sendMessage.setText("Пожалуйста, укажите теги (<code>YouTube</code>, <code>Twitch</code>, <code>YouTube+Twitch</code>).");
            sendMessage.setParseMode("HTML");
            executeMessage(sendMessage);
        } else if (state.equals("ADDING_BLOGGER_TAGS")) {
            User user = userRepository.findById(chatId).get();
            String[] parts = user.getTempData().split("\\|");
            if (parts.length == 2) {
                String nickname = parts[0];
                String link = parts[1];
                String tags = messageText;
                Blogger blogger = new Blogger();
                blogger.setNickname(nickname);
                blogger.setLink(link);
                blogger.setTags(tags);
                blogger.setPoints(0);
                bloggerRepository.save(blogger);
                sendMessage(chatId, "Блогер добавлен с ID: " + blogger.getId());
                user.setState(null);
                user.setTempData(null);
                userRepository.save(user);
                listBloggers(chatId, 1);
                syncMySQLToGoogleSheets(chatId);
            } else {
                sendMessage(chatId, "Ошибка при добавлении блогера.");
            }
        } else if (state.equals("WAITING_FOR_BLOGGER_ID")) {
            try {
                long bloggerId = Long.parseLong(messageText);
                Blogger blogger = bloggerRepository.findById(bloggerId).orElse(null);
                if (blogger != null) {
                    String info = "<b>Никнейм: </b>" + blogger.getNickname() + "\n"
                            + "<b>Ссылка: </b>" + blogger.getLink() + "\n"
                            + "<b>Теги: </b>" + blogger.getTags() + "\n"
                            + "<b>Осталось роликов: </b>" + blogger.getPoints();
                    sendMessageWithInlineKeyboard(chatId, info, blogger.getId());
                    setUserState(chatId, null);
                } else {
                    sendMessage(chatId, "Блогер не найден.");
                }
            } catch (NumberFormatException e) {
                sendMessage(chatId, "Неверный ID.");
            }
        } else if (state.startsWith("ADDING_POINTS_TO_BLOGGER_")) {
            long bloggerId = Long.parseLong(state.substring("ADDING_POINTS_TO_BLOGGER_".length()));
            try {
                int pointsToAdd = Integer.parseInt(messageText);
                if (pointsToAdd > 0) {
                    Blogger blogger = bloggerRepository.findById(bloggerId).orElse(null);
                    if (blogger != null) {
                        blogger.setPoints(blogger.getPoints() + pointsToAdd);
                        bloggerRepository.save(blogger);
                        sendMessage(chatId, "Добавлено " + pointsToAdd + " поинтов для блогера " + blogger.getNickname() + ". Всего поинтов: " + blogger.getPoints());
                        syncMySQLToGoogleSheets(chatId);
                    } else {
                        sendMessage(chatId, "Блогер не найден.");
                    }
                } else {
                    sendMessage(chatId, "Количество поинтов должно быть положительным.");
                }
            } catch (NumberFormatException e) {
                sendMessage(chatId, "Неверное число.");
            }
            setUserState(chatId, null);
            listBloggers(chatId, 1);
        } else if (state.startsWith("ADDING_VIDEO_FOR_BLOGGER_")) {
            String[] parts = state.split("_");
            if (parts.length == 7 && parts[0].equals("ADDING") && parts[1].equals("VIDEO") && parts[2].equals("FOR") && parts[3].equals("BLOGGER") && parts[5].equals("MESSAGE")) {
                try {
                    long bloggerId = Long.parseLong(parts[4]);
                    long originalMessageId = Long.parseLong(parts[6]);
                    String videoUrl = messageText;

                    Blogger blogger = bloggerRepository.findById(bloggerId).orElse(null);
                    if (blogger != null && blogger.getPoints() > 0) {
                        // Вычитаем 1 поинт
                        blogger.setPoints(blogger.getPoints() - 1);
                        bloggerRepository.save(blogger);

                        // Создаем и сохраняем видео
                        Video video = new Video();
                        video.setBlogger(blogger);
                        video.setUrl(videoUrl);
                        video.setAddedAt(new Timestamp(System.currentTimeMillis()));
                        videoRepository.save(video);

                        // Отправляем подтверждение
                        String text = "";
                        SendMessage sm = new SendMessage();
                        sm.setChatId(String.valueOf(chatId));


                        text += "Видео добавлено для блогера " + blogger.getNickname() + ". Осталось поинтов: " + convertToEmoji(blogger.getPoints())+ "\n";


                        switch (blogger.getPoints()) {

                            case 1:
                                text += "<b>⚠️ ВНИМАНИЕ ОСТАЛСЯ ТОЛЬКО 1 ПОИНТ</b>";

                                break;
                            case 0:
                                text += "<b>‼\uFE0F ТРЕВВОГА, ТРЕВОГА ПОИНТОВ НЕ ОСТАЛОСЬ СРОЧНО РЕШАЕМ ПРОБЛЕМУ</b>";
                                break;
                        }
                        sm.setParseMode(ParseMode.HTML);
                         sm.setText(text);
                        executeMessage(sm);

                        // Синхронизируем данные блогеров с Google Sheets
                        syncMySQLToGoogleSheets(chatId);
                    } else {
                        sendMessage(chatId, "Нельзя добавить видео: поинтов недостаточно или блогер не найден.");
                    }
                } catch (NumberFormatException e) {
                    sendMessage(chatId, "Ошибка: неверный ID.");
                }
                listBloggers(chatId, 0);
                setUserState(chatId, null); // Сбрасываем состояние
            }
        }
    }

    private void listBloggers(long chatId, int page) {
        List<Blogger> bloggers = bloggerRepository.findAll();
        if (bloggers.isEmpty()) {
            sendMessage(chatId, "Список блогеров пуст.");
            // return;
        }

        int pageSize = 10;
        int totalBloggers = bloggers.size();
        int totalPages = (int) Math.ceil((double) totalBloggers / pageSize);
        page = Math.max(1, Math.min(page, totalPages));

        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalBloggers);

        StringBuilder sb = new StringBuilder();
        sb.append("Список блогеров (Страница ").append(page).append(" из ").append(totalPages).append("):\n\n");
        for (int i = startIndex; i < endIndex; i++) {
            Blogger blogger = bloggers.get(i);
//<a href=\"https://example.com\">наш сайт</a>
            sb.append("ID: <code>").append(blogger.getId()).append("</code>").append(", Никнейм: <b>").append(blogger.getNickname()).append("</b>").append(", Теги: ").append(" <a href=\"").append(blogger.getLink()).append("\">").append(blogger.getTags()).append("</a>").append(",\nПоинты: ").append(convertToEmoji(blogger.getPoints())).append("\n\n");
        }

        SendMessage message = new SendMessage();
        message.setParseMode("HTML");
        message.setChatId(String.valueOf(chatId));
        message.setText(sb.toString());

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> navigationRow = new ArrayList<>();
        if (page > 1) {
            InlineKeyboardButton prevButton = new InlineKeyboardButton();
            prevButton.setText("⬅ Назад");
            prevButton.setCallbackData("PREV_PAGE_" + (page - 1));
            navigationRow.add(prevButton);
        }
        if (page < totalPages) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText("Вперед ➡");
            nextButton.setCallbackData("NEXT_PAGE_" + (page + 1));
            navigationRow.add(nextButton);
        }
        if (!navigationRow.isEmpty()) {
            rows.add(navigationRow);
        }

        List<InlineKeyboardButton> addBloggerRow = new ArrayList<>();
        InlineKeyboardButton addBloggerButton = new InlineKeyboardButton();
        addBloggerButton.setText("Добавить блогера");
        addBloggerButton.setCallbackData("ADD_BLOGGER");
        addBloggerRow.add(addBloggerButton);
        rows.add(addBloggerRow);

        List<InlineKeyboardButton> interactBloggerRow = new ArrayList<>();
        InlineKeyboardButton interactBloggerButton = new InlineKeyboardButton();
        interactBloggerButton.setText("Взаимодействовать с блогером");
        interactBloggerButton.setCallbackData("INTERACT_BLOGGER");
        interactBloggerRow.add(interactBloggerButton);
        rows.add(interactBloggerRow);

        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        message.setDisableWebPagePreview(true);
        executeMessage(message);
    }

    public static String convertToEmoji(int number) {
        Map<Character, String> emojiMap = Map.of('0', "0️⃣", '1', "1️⃣", '2', "2️⃣", '3', "3️⃣", '4', "4️⃣", '5', "5️⃣", '6', "6️⃣", '7', "7️⃣", '8', "8️⃣", '9', "9️⃣");

        StringBuilder emojiBuilder = new StringBuilder();
        for (char digit : String.valueOf(number).toCharArray()) {
            emojiBuilder.append(emojiMap.getOrDefault(digit, String.valueOf(digit)));
        }

        return emojiBuilder.toString();
    }

    private void sendMessageWithInlineKeyboard(long chatId, String text, long bloggerId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton addVideoButton = new InlineKeyboardButton();
        addVideoButton.setText("Добавить видео");
        addVideoButton.setCallbackData("ADD_VIDEO_" + bloggerId);
        row1.add(addVideoButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton addPointsButton = new InlineKeyboardButton();
        addPointsButton.setText("Добавить поинты");
        addPointsButton.setCallbackData("ADD_POINTS_" + bloggerId);
        row2.add(addPointsButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("Вернуться в список");
        backButton.setCallbackData("BACK");
        row3.add(backButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        message.setParseMode(ParseMode.HTML);
        executeMessage(message);
    }

    private void registerUser(Message msg) {
        if (userRepository.findById(msg.getChatId()).isEmpty()) {
            var chatId = msg.getChatId();
            var chat = msg.getChat();
            User user = new User();
            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));
            user.setAdmin(chatId == config.getOwnerId()); // Owner is admin by default
            userRepository.save(user);
            log.info("Пользователь сохранен: " + user);
        }
    }

    private void startCommandReceived(long chatId, String name) {
        if (isAdminOrOwner(chatId)) {
            setUserState(chatId, "LIST_BLOGGERS_PAGE_1");
            listBloggers(chatId, 1);
        } else {
            sendMessage(chatId, "У вас нет прав.");
        }
    }

    private void executeEditMessageText(String text, long chatId, long messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setMessageId((int) messageId);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR_TEXT + e.getMessage());
        }
    }

    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR_TEXT + e.getMessage());
        }
    }

    private void prepareAndSendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);
        executeMessage(message);
    }

    private void sendMessage(long chatId, String text) {
        prepareAndSendMessage(chatId, text);
    }

    private void setUserState(long chatId, String state) {
        User user = userRepository.findById(chatId).orElse(null);
        if (user != null) {
            user.setState(state);
            userRepository.save(user);
        }
    }

    private String getUserState(long chatId) {
        User user = userRepository.findById(chatId).orElse(null);
        return user != null ? user.getState() : null;
    }

    // Запланированная задача для ежедневной синхронизации MySQL -> Google Sheets
    @Scheduled(cron = "0 0 2 * * *") // Каждый день в 02:00
    private void autoSyncMySQLToGoogleSheets() {
        List<User> admins = userRepository.findByIsAdminTrue();
        List<Long> adminChatIds = new ArrayList<>();
        adminChatIds.add(config.getOwnerId());
        for (User admin : admins) {
            if (admin.getChatId() != config.getOwnerId()) {
                adminChatIds.add(admin.getChatId());
            }
        }

        for (Long chatId : adminChatIds) {
            syncMySQLToGoogleSheets(chatId);
        }
    }

    // Метод для экспорта данных из MySQL в Google Таблицу
    private void syncMySQLToGoogleSheets(long chatId) {
        try {
            // Получение данных из MySQL
            List<Blogger> bloggers = bloggerRepository.findAll();
            List<List<Object>> sheetData = new ArrayList<>();
            // Заголовки
            sheetData.add(Arrays.asList("ID", "Nickname", "Link", "Tags", "Points"));
            // Данные
            for (Blogger blogger : bloggers) {
                sheetData.add(Arrays.asList(blogger.getId(), blogger.getNickname(), blogger.getLink(), blogger.getTags(), blogger.getPoints()));
            }

            // Запись в Google Таблицу
            ValueRange body = new ValueRange().setValues(sheetData);
            sheetsService.spreadsheets().values().update(SPREADSHEET_ID, SHEET_NAME + "!A1", body).setValueInputOption("RAW").execute();
            syncVideosToGoogleSheets(chatId);
            // sendMessage(chatId, "Данные успешно синхронизированы с Google Таблицей.");
        } catch (IOException e) {
            log.error("Ошибка при синхронизации с Google Таблицей: " + e.getMessage());
            sendMessage(chatId, "Ошибка при синхронизации с Google Таблицей: " + e.getMessage());
        }
    }

    private void syncVideosToGoogleSheets(long chatId) {
        try {
            // Получаем все видео из базы данных
            List<Video> videos = videoRepository.findAll();

            // Сортируем видео по имени блогера
            List<Video> sortedVideos = videos.stream().sorted(Comparator.comparing(v -> v.getBlogger().getNickname())).collect(Collectors.toList());

            // Подготавливаем данные для Google Sheets
            List<List<Object>> sheetData = new ArrayList<>();
            sheetData.add(Arrays.asList("Имя блогера", "ID видео", "ссылки", "Дата добавления"));
            for (Video video : sortedVideos) {
                sheetData.add(Arrays.asList(video.getBlogger().getNickname(), video.getId().toString(), video.getUrl(), video.getAddedAt().toString()));
            }

            // Создаем объект ValueRange для передачи данных в Google Sheets
            ValueRange body = new ValueRange().setValues(sheetData);

            // Обновляем лист "Videos" в Google Таблице, начиная с ячейки A1
            sheetsService.spreadsheets().values().update(SPREADSHEET_ID, "VIDEOS_SHEET_NAME" + "!A1", body).setValueInputOption("RAW").execute();
        } catch (IOException e) {
            // Логируем ошибку и отправляем сообщение администратору
            log.error("Ошибка при синхронизации видео с Google Таблицей: " + e.getMessage());
            sendMessage(chatId, "Ошибка при синхронизации видео с Google Таблицей: " + e.getMessage());
        }
    }

}