package messenger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Консольный клиент мессенджера.
 * Команды пользователя:
 *   /register <логин> <пароль>  - регистрация и вход
 *   /login <логин> <пароль>     - вход
 *   /msg <кому> <текст>         - личное сообщение
 *   /online                     - список онлайн-пользователей
 *   /logout                     - выход из аккаунта (разрыв соединения)
 *   /exit                       - отключиться
 */
public class Client {

    private final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        new Client().connect(host, port);
    }

    public void connect(String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // Фоновый поток: печатает сообщения, приходящие с сервера
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while (running.get() && (line = in.readLine()) != null) {
                        printServerMessage(line);
                    }
                } catch (IOException e) {
                    // соединение закрыто
                } finally {
                    System.out.println("[Клиент] соединение с сервером закрыто.");
                    synchronized (this) { notifyAll(); }
                }
            }, "server-reader");
            reader.setDaemon(true);
            reader.start();

            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
            while (running.get()) {
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;
                String command = toProtocol(input);
                if (command == null) continue; // локальная команда (help)
                out.println(command);
                if (input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/logout")) {
                    break;
                }
            }
            running.set(false);
            // Даём фоновому потоку долю секунды дочитать последние ответы сервера
            try { reader.join(500); } catch (InterruptedException ignored) {}
        }
    }

    /** Преобразует пользовательскую команду в строку протокола. */
    private String toProtocol(String input) {
        String[] parts = input.split("\\s+", 4);
        switch (parts[0].toLowerCase()) {
            case "/register":
                if (parts.length < 3) { System.out.println("Использование: /register <логин> <пароль>"); return null; }
                return Message.CMD_REGISTER + ":" + parts[1] + ":" + parts[2];
            case "/login":
                if (parts.length < 3) { System.out.println("Использование: /login <логин> <пароль>"); return null; }
                return Message.CMD_LOGIN + ":" + parts[1] + ":" + parts[2];
            case "/msg":
                if (parts.length < 3) { System.out.println("Использование: /msg <кому> <текст>"); return null; }
                String text = parts.length == 4 ? parts[3] : "";
                return Message.CMD_SEND + ":" + parts[1] + ":" + text;
            case "/online":
                return Message.CMD_LIST;
            case "/logout":
                return Message.CMD_LOGOUT;
            case "/exit":
            case "/quit":
                return Message.CMD_QUIT;
            case "/help":
                System.out.println("Команды: /register <логин> <пароль>, /login <логин> <пароль>,");
                System.out.println("         /msg <кому> <текст>, /online, /logout, /exit");
                return null;
            default:
                System.out.println("Неизвестная команда '" + parts[0] + "'. Наберите /help");
                return null;
        }
    }

    /** Красивый вывод сообщений от сервера. */
    private void printServerMessage(String line) {
        Message msg = Message.parse(line);
        if (msg == null) return;
        switch (msg.command) {
            case Message.CMD_MESSAGE: {
                int idx = msg.args.indexOf(':');
                String from = idx > 0 ? msg.args.substring(0, idx) : "?";
                String body = idx > 0 ? msg.args.substring(idx + 1) : msg.args;
                System.out.println("\n[" + from + "]: " + body + "\n> ");
                break;
            }
            case Message.CMD_SYSTEM:
                System.out.println("\n** " + msg.args + "\n> ");
                break;
            case Message.CMD_OK:
                System.out.println("\n--> " + msg.args + "\n> ");
                break;
            case Message.CMD_ERROR:
                System.out.println("\n!! Ошибка: " + msg.args + "\n> ");
                break;
            default:
                System.out.println("\n" + line + "\n> ");
        }
    }
}
