package messenger;

/**
 * Протокол обмена: каждый обмен — одна строка "КОМАНДА:аргументы".
 */
public final class Message {

    // Команды клиент -> сервер
    public static final String CMD_REGISTER = "REGISTER"; // REGISTER:пароль
    public static final String CMD_LOGIN    = "LOGIN";    // LOGIN:пароль
    public static final String CMD_SEND     = "SEND";     // SEND:получатель:текст
    public static final String CMD_LIST     = "LIST";     // LIST
    public static final String CMD_LOGOUT   = "LOGOUT";   // LOGOUT
    public static final String CMD_QUIT     = "QUIT";     // QUIT

    // Команды сервер -> клиент
    public static final String CMD_OK       = "OK";       // OK:текст
    public static final String CMD_ERROR    = "ERROR";    // ERROR:текст
    public static final String CMD_MESSAGE  = "MESSAGE";  // MESSAGE:отправитель:текст
    public static final String CMD_SYSTEM   = "SYSTEM";   // SYSTEM:текст

    public final String command;
    public final String args;

    public Message(String command, String args) {
        this.command = command;
        this.args = args;
    }

    public String serialize() {
        return args == null ? command : command + ":" + args;
    }

    /** Разбирает строку протокола. Возвращает null при некорректном формате. */
    public static Message parse(String line) {
        if (line == null || line.isEmpty()) return null;
        int idx = line.indexOf(':');
        if (idx < 0) {
            return new Message(line.toUpperCase(), null);
        }
        return new Message(line.substring(0, idx).toUpperCase(), line.substring(idx + 1));
    }
}
