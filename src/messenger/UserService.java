package messenger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранение пользователей (имя -> хэш пароля) с сохранением в файл.
 * Пароли в открытом виде не хранятся (SHA-256).
 */
public class UserService {

    private final Path storageFile;
    private final Map<String, String> users = new ConcurrentHashMap<>(); // login -> passwordHash

    public UserService(Path storageFile) throws IOException {
        this.storageFile = storageFile;
        load();
    }

    private void load() throws IOException {
        if (!Files.exists(storageFile)) return;
        for (String line : Files.readAllLines(storageFile, StandardCharsets.UTF_8)) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                users.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
    }

    private synchronized void save() throws IOException {
        List<String> lines = new ArrayList<>();
        users.forEach((login, hash) -> lines.add(login + ":" + hash));
        Files.write(storageFile, lines, StandardCharsets.UTF_8);
    }

    /** Регистрирует нового пользователя. Возвращает true при успехе. */
    public synchronized boolean register(String login, String password) throws IOException {
        if (users.containsKey(login)) return false;
        users.put(login, sha256(password));
        save();
        return true;
    }

    /** Проверяет пару логин/пароль. */
    public boolean authenticate(String login, String password) {
        String hash = users.get(login);
        return hash != null && hash.equals(sha256(password));
    }

    public boolean exists(String login) {
        return users.containsKey(login);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
