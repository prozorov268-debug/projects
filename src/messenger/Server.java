package messenger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Сервер мессенджера. Принимает TCP-соединения, обслуживает каждого
 * клиента отдельным потоком. Поддерживает регистрацию, вход,
 * доставку личных сообщений и уведомления о входе/выходе.
 */
public class Server {

    private final int port;
    private final UserService userService;
    /** Активные сессии: login -> клиент */
    private final Map<String, ClientHandler> online = new ConcurrentHashMap<>();
    /** Оффлайн-почта: login -> очередь сообщений */
    private final Map<String, Queue<String>> offlineMail = new ConcurrentHashMap<>();

    public Server(int port, Path dataFile) throws IOException {
        this.port = port;
        this.userService = new UserService(dataFile);
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("[Сервер] запущен на порту " + port);
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                System.out.println("[Сервер] новое соединение: " + socket.getRemoteSocketAddress());
                Thread t = new Thread(new ClientHandler(socket), "client-" + socket.getPort());
                t.setDaemon(true);
                t.start();
            }
        }
    }

    /** Отправляет письмо получателю: сразу, если онлайн, иначе в оффлайн-очередь. */
    private void deliver(String from, String to, String text) {
        ClientHandler target = online.get(to);
        if (target != null) {
            target.send(new Message(Message.CMD_MESSAGE, from + ":" + text));
        } else {
            offlineMail.computeIfAbsent(to, k -> new ConcurrentLinkedQueue<>())
                       .add(from + ":" + text);
        }
    }

    private void broadcastSystem(String text, String exceptLogin) {
        Message m = new Message(Message.CMD_SYSTEM, text);
        online.forEach((login, handler) -> {
            if (!login.equals(exceptLogin)) handler.send(m);
        });
    }

    /** Обработчик одного клиентского соединения. */
    private class ClientHandler implements Runnable {

        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String login; // null, пока клиент не вошёл в систему

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (socket) {
                out = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                send(new Message(Message.CMD_SYSTEM,
                        "Добро пожаловать! Команды: /register <пароль>, /login <пароль>, " +
                        "/msg <кому> <текст>, /online, /logout, /exit"));

                String line;
                while ((line = in.readLine()) != null) {
                    Message msg = Message.parse(line);
                    if (msg == null) continue;
                    if (!handle(msg)) break; // QUIT или ошибка протокола
                }
            } catch (IOException e) {
                // клиент отключился — штатная ситуация
            } finally {
                logoutQuietly();
                System.out.println("[Сервер] соединение закрыто: " + socket.getRemoteSocketAddress());
            }
        }

        /** Возвращает false, если соединение нужно закрыть. */
        private boolean handle(Message msg) {
            try {
                switch (msg.command) {
                    case Message.CMD_REGISTER: {
                        if (login != null) {
                            send(new Message(Message.CMD_ERROR, "Вы уже в системе"));
                            break;
                        }
                        // Формат: REGISTER:логин:пароль
                        String[] parts = splitArgs(msg.args, 2);
                        if (parts == null || parts[0].isEmpty() || parts[1].isEmpty()) {
                            send(new Message(Message.CMD_ERROR, "Формат: REGISTER:логин:пароль"));
                            break;
                        }
                        if (userService.register(parts[0], parts[1])) {
                            login = parts[0];
                            online.put(login, this);
                            send(new Message(Message.CMD_OK,
                                    "Регистрация выполнена. Вы вошли как " + login));
                            deliverOfflineMail();
                            broadcastSystem(login + " зарегистрировался(ась) и в сети", login);
                        } else {
                            send(new Message(Message.CMD_ERROR, "Логин уже занят"));
                        }
                        break;
                    }
                    case Message.CMD_LOGIN: {
                        if (login != null) {
                            send(new Message(Message.CMD_ERROR, "Вы уже в системе"));
                            break;
                        }
                        String[] parts = splitArgs(msg.args, 2);
                        if (parts == null || parts[0].isEmpty() || parts[1].isEmpty()) {
                            send(new Message(Message.CMD_ERROR, "Формат: LOGIN:логин:пароль"));
                            break;
                        }
                        if (userService.authenticate(parts[0], parts[1])) {
                            login = parts[0];
                            online.put(login, this);
                            send(new Message(Message.CMD_OK, "Вход выполнен. Привет, " + login + "!"));
                            deliverOfflineMail();
                            broadcastSystem(login + " теперь в сети", login);
                        } else {
                            send(new Message(Message.CMD_ERROR, "Неверный логин или пароль"));
                        }
                        break;
                    }
                    case Message.CMD_SEND: {
                        if (login == null) {
                            send(new Message(Message.CMD_ERROR, "Сначала войдите в систему"));
                            break;
                        }
                        String[] parts = splitArgs(msg.args, 2);
                        if (parts == null || parts.length < 2 || parts[0].isEmpty()) {
                            send(new Message(Message.CMD_ERROR, "Формат: SEND:кому:текст"));
                            break;
                        }
                        String to = parts[0];
                        String text = parts[1];
                        if (!userService.exists(to)) {
                            send(new Message(Message.CMD_ERROR, "Пользователь " + to + " не существует"));
                            break;
                        }
                        if (to.equals(login)) {
                            send(new Message(Message.CMD_ERROR, "Нельзя отправить сообщение самому себе"));
                            break;
                        }
                        deliver(login, to, text);
                        send(new Message(Message.CMD_OK,
                                online.containsKey(to) ? "Сообщение доставлено" : "Получатель не в сети — сохранено"));
                        break;
                    }
                    case Message.CMD_LIST: {
                        if (login == null) {
                            send(new Message(Message.CMD_ERROR, "Сначала войдите в систему"));
                            break;
                        }
                        List<String> names = new ArrayList<>(online.keySet());
                        Collections.sort(names);
                        send(new Message(Message.CMD_OK, "Онлайн (" + names.size() + "): "
                                + String.join(", ", names)));
                        break;
                    }
                    case Message.CMD_LOGOUT: {
                        if (login == null) {
                            send(new Message(Message.CMD_ERROR, "Вы не в системе"));
                            break;
                        }
                        send(new Message(Message.CMD_OK, "До свидания!"));
                        return false;
                    }
                    case Message.CMD_QUIT: {
                        return false;
                    }
                    default:
                        send(new Message(Message.CMD_ERROR, "Неизвестная команда: " + msg.command));
                }
                return true;
            } catch (Exception e) {
                send(new Message(Message.CMD_ERROR, "Внутренняя ошибка сервера"));
                return true;
            }
        }

        private void deliverOfflineMail() {
            Queue<String> mail = offlineMail.remove(login);
            if (mail == null) return;
            String text;
            while ((text = mail.poll()) != null) {
                int idx = text.indexOf(':');
                String from = idx > 0 ? text.substring(0, idx) : "?";
                String body = idx > 0 ? text.substring(idx + 1) : text;
                send(new Message(Message.CMD_MESSAGE, from + ":" + body));
            }
        }

        private void logoutQuietly() {
            if (login != null) {
                online.remove(login, this);
                broadcastSystem(login + " вышел(ла) из сети", login);
                login = null;
            }
        }

        void send(Message msg) {
            if (out != null) {
                out.println(msg.serialize());
            }
        }
    }

    /** Разделяет args на count частей, последняя часть может содержать ':' */
    private static String[] splitArgs(String args, int count) {
        if (args == null) return null;
        String[] parts = new String[count];
        for (int i = 0; i < count - 1; i++) {
            int idx = args.indexOf(':');
            if (idx < 0) return null;
            parts[i] = args.substring(0, idx);
            args = args.substring(idx + 1);
        }
        parts[count - 1] = args;
        return parts;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        Path dataFile = Paths.get("users.dat");
        new Server(port, dataFile).start();
    }
}
