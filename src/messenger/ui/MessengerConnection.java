package messenger.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import messenger.Message;

/**
 * Сетевой слой GUI-клиента: TCP-соединение с сервером мессенджера
 * по существующему текстовому протоколу (REGISTER/LOGIN/SEND/LIST/...).
 *
 * Читает ответы сервера в отдельном демон-потоке и передаёт их слушателю
 * (Listener). Все публичные методы потокобезопасны: запись в сокет
 * синхронизирована по монитору {@code writeLock}.
 */
public class MessengerConnection {

    /** Callback для событий от сервера. Вызывается из фонового потока чтения! */
    public interface Listener {
        void onConnected();
        /** msg — разобранный ответ сервера (OK / ERROR / MESSAGE / SYSTEM). */
        void onServerMessage(Message msg);
        void onDisconnected(String reason);
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final String host;
    private final int port;
    private final Listener listener;

    private final Object writeLock = new Object();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile BufferedReader in;
    private volatile Thread readerThread;

    public MessengerConnection(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    /**
     * Подключается к серверу. Блокирующий вызов — выполнять из фонового потока.
     * @return true, если подключение удалось
     */
    public boolean connect() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket = s;
            out = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            connected.set(true);
            startReader();
            listener.onConnected();
            return true;
        } catch (IOException e) {
            closeQuietly();
            return false;
        }
    }

    /** Отправляет строку протокола серверу. Потокобезопасно. */
    public void sendRaw(String line) {
        synchronized (writeLock) {
            PrintWriter w = out;
            if (connected.get() && w != null) {
                w.println(line);
            }
        }
    }

    public void register(String login, String password) {
        sendRaw(Message.CMD_REGISTER + ":" + login + ":" + password);
    }

    public void login(String login, String password) {
        sendRaw(Message.CMD_LOGIN + ":" + login + ":" + password);
    }

    public void send(String to, String text) {
        sendRaw(Message.CMD_SEND + ":" + to + ":" + text);
    }

    public void list() {
        sendRaw(Message.CMD_LIST);
    }

    public void logout() {
        sendRaw(Message.CMD_LOGOUT);
    }

    public void quit() {
        sendRaw(Message.CMD_QUIT);
    }

    public boolean isConnected() {
        return connected.get();
    }

    /** Закрывает соединение; генерирует onDisconnected, если оно было активным. */
    public void disconnect(String reason) {
        if (!connected.getAndSet(false)) {
            return; // уже отключены — не дублируем событие
        }
        closeQuietly();
        listener.onDisconnected(reason == null ? "Соединение закрыто" : reason);
    }

    public void disconnect() {
        disconnect("Отключено");
    }

    // ------------------------------------------------------------------

    /** Фоновый поток чтения: читает строки сервера до конца потока/ошибки. */
    private void startReader() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (connected.get() && (line = in.readLine()) != null) {
                    Message msg = Message.parse(line);
                    if (msg != null) {
                        listener.onServerMessage(msg);
                    }
                }
                if (connected.getAndSet(false)) {
                    listener.onDisconnected("Сервер закрыл соединение");
                }
            } catch (SocketTimeoutException e) {
                if (connected.getAndSet(false)) {
                    listener.onDisconnected("Сервер не отвечает (таймаут)");
                }
            } catch (IOException e) {
                if (connected.getAndSet(false)) {
                    listener.onDisconnected("Обрыв соединения: " + e.getMessage());
                }
            } finally {
                closeQuietly();
            }
        }, "gui-server-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void closeQuietly() {
        try {
            Socket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
        in = null;
    }
}
