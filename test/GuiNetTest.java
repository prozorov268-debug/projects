import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import messenger.Message;
import messenger.ui.MessengerConnection;

/**
 * Headless-тест сетевого слоя GUI (MessengerConnection): без Swing/дисплея.
 * Проверяет: быстрый отказ при недоступном сервере, подключение к рабочему
 * серверу, REGISTER/LIST/SEND, приём MESSAGE и SYSTEM, ERROR-ответы,
 * завершение по quit с событием onDisconnected.
 */
public class GuiNetTest {

    static final String HOST = "localhost";
    static int PORT;

    public static void main(String[] args) throws Exception {
        PORT = Integer.parseInt(args[0]); // порт тестового сервера

        // --- 1. Отрицательный кейс: сервера нет -> connect() возвращает false быстро ---
        MessengerConnection bad = new MessengerConnection(HOST, freePort(), noOpListener());
        long t0 = System.currentTimeMillis();
        boolean okBad = bad.connect();
        long elapsed = System.currentTimeMillis() - t0;
        check(!okBad, "connect к недоступному порту возвращает false");
        check(elapsed < 5000, "отказ быстрый (" + elapsed + " мс, есть таймаут подключения)");

        // --- 2. alice: подключение, регистрация, LIST ---
        Recorder al = new Recorder();
        MessengerConnection alice = new MessengerConnection(HOST, PORT, al);
        check(alice.connect(), "alice подключилась");
        al.waitFor("SYSTEM|", "Добро пожаловать");   // приветствие сервера
        alice.register("alice", "secret1");
        al.waitFor("OK|", "Регистрация выполнена");

        alice.list();
        al.waitFor("OK|", "Онлайн");                 // список содержит alice — проверим ниже
        al.waitForInLast("OK|", "alice");

        // --- 3. bob заходит; alice получает SYSTEM и видит его в LIST ---
        Recorder bl = new Recorder();
        MessengerConnection bob = new MessengerConnection(HOST, PORT, bl);
        check(bob.connect(), "bob подключился");
        bob.register("bob", "secret2"); // в чистом каталоге bob ещё не зарегистрирован
        bl.waitFor("OK|", "Регистрация выполнена");

        al.waitFor("SYSTEM|", "bob");
        alice.list();
        al.waitForInLast("OK|", "bob");

        // --- 4. двусторонняя переписка через GUI-слой (UTF-8) ---
        bob.send("alice", "Привет от GUI-слоя!");
        bl.waitFor("OK|", "доставлено");
        String got = al.waitForInLast("MESSAGE|", "bob:Привет от GUI-слоя!");
        check(got.equals("MESSAGE|bob:Привет от GUI-слоя!"), "текст дошёл без искажений: " + got);

        alice.send("bob", "Ответ из фонового потока");
        bl.waitForInLast("MESSAGE|", "alice:Ответ из фонового потока");

        // --- 5. невалидные данные -> ERROR (GUI показывает их в статусе) ---
        alice.login("ghost", "nope");
        al.waitFor("ERROR|", null);

        // --- 6. quit: сервер закрывает соединение -> onDisconnected ---
        alice.quit();
        al.waitForDisconnect(3000);
        check(!alice.isConnected(), "после quit isConnected()==false");

        bob.disconnect("тест завершён");
        bl.waitForDisconnect(3000);

        System.out.println("GUI-NET-TEST-PASS");
    }

    static MessengerConnection.Listener noOpListener() {
        return new MessengerConnection.Listener() {
            @Override public void onConnected() {}
            @Override public void onServerMessage(Message msg) {}
            @Override public void onDisconnected(String reason) {}
        };
    }

    /** Слушатель, накапливающий события (из фонового потока) для ожидания в main. */
    static class Recorder implements MessengerConnection.Listener {
        private final StringBuilder log = new StringBuilder();

        @Override public synchronized void onConnected() { record("CONNECTED", null); }
        @Override public synchronized void onServerMessage(Message msg) {
            record(msg.command, msg.args);
        }
        @Override public synchronized void onDisconnected(String reason) {
            record("DISCONNECTED", reason);
        }

        private void record(String prefix, String args) {
            synchronized (this) {
                log.append(prefix).append('|');
                if (args != null) log.append(args);
                log.append('\n');
                notifyAll();
            }
        }

        /** Ждёт событие вида "PREFIX|...contains..." где угодно в логе. */
        synchronized String waitFor(String prefix, String contains) throws Exception {
            return waitImpl(prefix, contains, false);
        }

        /** Ждёт событие PREFIX с contains только в ПОСЛЕДНЕЙ строке (детерминизм LIST). */
        synchronized String waitForInLast(String prefix, String contains) throws Exception {
            return waitImpl(prefix, contains, true);
        }

        private String waitImpl(String prefix, String contains, boolean lastOnly) throws Exception {
            long deadline = System.currentTimeMillis() + 4000;
            while (true) {
                String[] lines = log.toString().split("\n");
                for (int i = lastOnly ? lines.length - 1 : 0;
                     i < lines.length; i++) {
                    String line = lines[i];
                    if (line.startsWith(prefix)
                            && (contains == null || line.contains(contains))) {
                        return line;
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("Timeout waiting '" + prefix + contains
                            + "'. Log:\n" + log);
                }
                wait(50);
            }
        }

        synchronized void waitForDisconnect(long ms) throws Exception {
            long deadline = System.currentTimeMillis() + ms;
            while (true) {
                if (log.indexOf("DISCONNECTED") >= 0) return;
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("No disconnect event. Log:\n" + log);
                }
                wait(50);
            }
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("FAIL: " + what);
        System.out.println("ok - " + what);
    }
}
