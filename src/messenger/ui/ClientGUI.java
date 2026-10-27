package messenger.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import messenger.Message;

/**
 * Графический (Swing) клиент мессенджера. Работает поверх существующего
 * TCP-протокола — сервер и консольный клиент менять не требуется.
 *
 * Экраны (CardLayout):
 *   "connect" — индикатор подключения + кнопка «Переподключиться»;
 *   "login"   — вход/регистрация (логин, пароль, кнопки, статус);
 *   "chat"    — чат: список онлайн-пользователей, история сообщений,
 *               поле получателя, поле текста, кнопки «Отправить» / «Выйти».
 *
 * Потоки: сеть обслуживает фоновый поток чтения MessengerConnection;
 * попытки подключения — отдельный поток auto-connect; все изменения UI —
 * только через SwingUtilities.invokeLater (или Swing Timer, он на EDT).
 *
 * Параметры подключения по приоритету: аргументы командной строки >
 * переменные окружения MESSENGER_HOST / MESSENGER_PORT > localhost:5000.
 */
public class ClientGUI extends JFrame implements MessengerConnection.Listener {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Авто-подключение: 3 попытки с интервалом 2 секунды. */
    private static final int CONNECT_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_DELAY_MS = 2000;
    /** Период автоматического обновления списка онлайн (LIST). */
    private static final int LIST_REFRESH_PERIOD_MS = 10_000;

    // Иdentifiers экранов CardLayout
    private static final String CARD_CONNECT = "connect";
    private static final String CARD_LOGIN   = "login";
    private static final String CARD_CHAT    = "chat";

    private final String host;
    private final int port;

    /** Активное соединение; подменяется каждой успешной попыткой connect(). */
    private volatile MessengerConnection connection;

    // --- экран подключения ---
    private JPanel cardsPanel;
    private JLabel connectStatusLabel;
    private JButton reconnectButton;

    // --- экран входа/регистрации ---
    private JTextField loginField;
    private JPasswordField passwordField;
    private JLabel authStatusLabel;

    // --- экран чата ---
    private DefaultListModel<String> onlineModel;
    private JTextArea historyArea;
    private JTextField recipientField;
    private JTextField messageField;
    private Timer listRefreshTimer;
    private JLabel statusLabel;

    /** Логин после успешного REGISTER/LOGIN; null — гость (не в системе). */
    private volatile String currentLogin;
    /** true, когда пользователь сам закрыл окно — переподключение не нужно. */
    private volatile boolean shuttingDown = false;

    public ClientGUI(String host, int port) {
        this.host = host;
        this.port = port;
        setTitle("Мессенджер — " + host + ":" + port);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onWindowClosing(); }
        });
        setLayout(new BorderLayout());
        add(buildCards(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        setSize(760, 540);
        setMinimumSize(new Dimension(520, 380));
        setLocationRelativeTo(null);
    }

    // =====================================================================
    // Построение интерфейса
    // =====================================================================

    private JPanel buildCards() {
        cardsPanel = new JPanel(new CardLayout());
        cardsPanel.add(buildConnectCard(), CARD_CONNECT);
        cardsPanel.add(buildLoginCard(), CARD_LOGIN);
        cardsPanel.add(buildChatCard(), CARD_CHAT);
        showCard(CARD_CONNECT);
        return cardsPanel;
    }

    private void showCard(String name) {
        ((CardLayout) cardsPanel.getLayout()).show(cardsPanel, name);
    }

    /** Центрирование компонента в BoxLayout (setAlignmentX есть только у JComponent). */
    private static void centerAxis(javax.swing.JComponent c) {
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    /** Экран ожидания подключения с кнопкой «Переподключиться». */
    private JPanel buildConnectCard() {
        JPanel card = new JPanel(new GridBagLayout());
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Подключение к серверу...");
        centerAxis(title);
        connectStatusLabel = new JLabel(" ");
        centerAxis(connectStatusLabel);
        reconnectButton = new JButton("Переподключиться");
        centerAxis(reconnectButton);
        reconnectButton.setEnabled(false); // активна, когда попытки исчерпаны
        reconnectButton.addActionListener(e -> startAutoConnect());

        box.add(title);
        box.add(Box.createVerticalStrut(10));
        box.add(connectStatusLabel);
        box.add(Box.createVerticalStrut(15));
        box.add(reconnectButton);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);
        card.add(box, gbc);
        return card;
    }

    /** Экран входа/регистрации. */
    private JPanel buildLoginCard() {
        JPanel card = new JPanel(new GridBagLayout());
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Вход в мессенджер"));

        loginField = new JTextField(15);
        passwordField = new JPasswordField(15);
        authStatusLabel = new JLabel(" ");

        JButton loginButton = new JButton("Войти");
        JButton registerButton = new JButton("Зарегистрироваться");
        loginButton.addActionListener(e -> doAuth(false));
        registerButton.addActionListener(e -> doAuth(true));

        // Enter в полях = «Войти»
        loginField.addActionListener(e -> doAuth(false));
        passwordField.addActionListener(e -> doAuth(false));

        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; form.add(new JLabel("Логин:"), g);
        g.gridx = 1; g.gridy = 0; form.add(loginField, g);
        g.gridx = 0; g.gridy = 1; form.add(new JLabel("Пароль:"), g);
        g.gridx = 1; g.gridy = 1; form.add(passwordField, g);

        g.gridx = 0; g.gridy = 2; g.gridwidth = 2;
        g.fill = GridBagConstraints.NONE; g.anchor = GridBagConstraints.CENTER;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(loginButton);
        buttons.add(registerButton);
        form.add(buttons, g);

        g.gridy = 3; g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        form.add(authStatusLabel, g);

        card.add(form, new GridBagConstraints());
        return card;
    }

    /** Экран чата: слева — онлайн-список, справа — история и панель отправки. */
    private JPanel buildChatCard() {
        JPanel card = new JPanel(new BorderLayout());

        // Список онлайн-пользователей (обновляется ответами LIST и таймером)
        onlineModel = new DefaultListModel<>();
        JList<String> onlineList = new JList<>(onlineModel);
        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Двойной клик по имени — подставить получателя в поле «Кому»
        onlineList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String sel = onlineList.getSelectedValue();
                    if (sel != null && !sel.equals(currentLogin)) {
                        recipientField.setText(sel);
                        messageField.requestFocusInWindow();
                    }
                }
            }
        });
        JScrollPane onlineScroll = new JScrollPane(onlineList);
        onlineScroll.setBorder(BorderFactory.createTitledBorder("Онлайн"));

        JButton refreshButton = new JButton("Обновить список");
        refreshButton.addActionListener(e -> requestList());

        JPanel left = new JPanel(new BorderLayout());
        left.add(onlineScroll, BorderLayout.CENTER);
        left.add(refreshButton, BorderLayout.SOUTH);

        // История сообщений (только чтение)
        historyArea = new JTextArea();
        historyArea.setEditable(false);
        historyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        historyArea.setLineWrap(true);
        historyArea.setWrapStyleWord(true);
        JScrollPane historyScroll = new JScrollPane(historyArea);
        historyScroll.setBorder(BorderFactory.createTitledBorder("История сообщений"));

        // Панель отправки: кому + текст + кнопки
        recipientField = new JTextField(10);
        recipientField.setToolTipText("Логин получателя (или двойной клик по имени в списке слева)");
        messageField = new JTextField(25);
        messageField.addActionListener(e -> doSend());

        JButton sendButton = new JButton("Отправить");
        sendButton.addActionListener(e -> doSend());

        JButton logoutButton = new JButton("Выйти");
        logoutButton.addActionListener(e -> doLogout());

        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        inputPanel.add(new JLabel("Кому:"));
        inputPanel.add(recipientField);
        inputPanel.add(new JLabel("Сообщение:"));
        inputPanel.add(messageField);
        inputPanel.add(sendButton);
        inputPanel.add(logoutButton);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, historyScroll);
        split.setDividerLocation(190);

        JPanel right = new JPanel(new BorderLayout());
        right.add(split, BorderLayout.CENTER);
        right.add(inputPanel, BorderLayout.SOUTH);

        card.add(right, BorderLayout.CENTER);
        return card;
    }

    /** Нижняя статусная строка: состояние подключения + адрес сервера. */
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusLabel = new JLabel("Не подключено");
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(new JLabel(host + ":" + port), BorderLayout.EAST);
        return bar;
    }

    // =====================================================================
    // Сетевые события (приходят из фонового потока — на EDT через invokeLater)
    // =====================================================================

    @Override
    public void onConnected() {
        SwingUtilities.invokeLater(() -> {
            setStatus("Подключено" + (currentLogin != null ? ", " + currentLogin : ""));
            reconnectButton.setEnabled(false);
            connectStatusLabel.setText("Подключение установлено.");
            // После успешного подключения автоматически показываем нужный экран:
            // гостю — вход/регистрация, вошедшему (после реконнекта) — чат
            if (currentLogin == null) {
                showCard(CARD_LOGIN);
                authStatusLabel.setText("Соединение установлено. Войдите или зарегистрируйтесь.");
                loginField.requestFocusInWindow();
            } else {
                showCard(CARD_CHAT);
                requestList();
                startListRefresh();
            }
        });
    }

    @Override
    public void onServerMessage(Message msg) {
        SwingUtilities.invokeLater(() -> handleServerMessageOnEdt(msg));
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (shuttingDown) return;
            stopListRefresh();
            onlineModel.clear();
            setStatus("Отключено: " + reason);
            appendHistory("** Соединение потеряно (" + reason + "). Переподключение...\n");
            showCard(CARD_CONNECT);
            startAutoConnect(); // автоматические повторные попытки
        });
    }

    /** Обработка ответов сервера. Вызывается только на EDT. */
    private void handleServerMessageOnEdt(Message msg) {
        switch (msg.command) {
            case Message.CMD_OK: {
                String args = msg.args == null ? "" : msg.args;
                if (args.startsWith("Онлайн")) {
                    updateOnlineList(args);
                    if (currentLogin != null) setStatus("Подключено, " + currentLogin);
                } else if (args.contains("Регистрация выполнена") || args.contains("Вход выполнен")) {
                    currentLogin = extractLoginFromOk(args);
                    authStatusLabel.setText("<html><span color='green'>" + escapeHtml(args) + "</span></html>");
                    appendHistory("--> " + args + "\n");
                    showCard(CARD_CHAT);
                    requestList();
                    startListRefresh();
                    messageField.requestFocusInWindow();
                } else {
                    appendHistory("--> " + args + "\n"); // например, статус доставки SEND
                }
                break;
            }
            case Message.CMD_ERROR: {
                // На экране входа — в метку статуса формы; в чате — в историю и статус-бар
                if (currentLogin == null) {
                    authStatusLabel.setText("<html><span color='red'>Ошибка: "
                            + escapeHtml(msg.args) + "</span></html>");
                } else {
                    appendHistory("!! Ошибка: " + msg.args + "\n");
                    setStatus("Ошибка: " + msg.args);
                }
                break;
            }
            case Message.CMD_MESSAGE: {
                int idx = msg.args == null ? -1 : msg.args.indexOf(':');
                String from = idx > 0 ? msg.args.substring(0, idx) : "?";
                String body = idx > 0 ? msg.args.substring(idx + 1) : String.valueOf(msg.args);
                appendHistory("[" + now() + "] " + from + ": " + body + "\n");
                break;
            }
            case Message.CMD_SYSTEM: {
                appendHistory("[" + now() + "] ** " + msg.args + "\n");
                requestList(); // вход/выход кого-то — обновляем список
                break;
            }
            default:
                appendHistory(msg.serialize() + "\n");
        }
    }

    // =====================================================================
    // Действия пользователя
    // =====================================================================

    /**
     * Авто-подключение в фоновом потоке: до CONNECT_ATTEMPTS попыток
     * с интервалом CONNECT_RETRY_DELAY_MS мс. Каждую попытку создаётся
     * новый MessengerConnection; при успехе он становится активным.
     */
    private void startAutoConnect() {
        setStatus("Подключение к " + host + ":" + port + "...");
        connectStatusLabel.setText("Идёт подключение...");
        reconnectButton.setEnabled(false);
        Thread t = new Thread(() -> {
            for (int attempt = 1; attempt <= CONNECT_ATTEMPTS && !shuttingDown; attempt++) {
                MessengerConnection c = new MessengerConnection(host, port, ClientGUI.this);
                // connect() сам вызовет onConnected() и запустит поток чтения
                if (c.connect()) {
                    setActiveConnection(c);
                    return;
                }
                final int failed = attempt;
                SwingUtilities.invokeLater(() -> connectStatusLabel.setText(
                        "Сервер недоступен. Попытка " + failed + " из " + CONNECT_ATTEMPTS + "..."));
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!shuttingDown) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Не подключено");
                    connectStatusLabel.setText(
                            "<html>Не удалось подключиться к серверу <b>" + host + ":" + port + "</b>.<br>"
                          + "Убедитесь, что сервер запущен:<br>"
                          + "<tt>java -cp out messenger.Server " + port + "</tt><br><br>"
                          + "Нажмите «Переподключиться», чтобы повторить попытки.</html>");
                    reconnectButton.setEnabled(true);
                });
            }
        }, "auto-connect");
        t.setDaemon(true);
        t.start();
    }

    /** Заменяет активное соединение, закрывая предыдущее (если оно осталось). */
    private synchronized void setActiveConnection(MessengerConnection fresh) {
        MessengerConnection old = connection;
        connection = fresh;
        if (old != null && old != fresh) {
            old.disconnect("Заменено новым соединением");
        }
    }

    /** Вход (register=false) или регистрация (register=true). */
    private void doAuth(boolean register) {
        String user = loginField.getText().trim();
        String pass = new String(passwordField.getPassword());
        if (user.isEmpty() || pass.isEmpty()) {
            authStatusLabel.setText("<html><span color='red'>Заполните логин и пароль</span></html>");
            return;
        }
        MessengerConnection c = connection;
        if (c == null || !c.isConnected()) {
            authStatusLabel.setText("<html><span color='red'>Нет соединения с сервером</span></html>");
            return;
        }
        authStatusLabel.setText("Отправка запроса...");
        if (register) c.register(user, pass);
        else c.login(user, pass);
    }

    /** Отправка личного сообщения выбранному получателю. */
    private void doSend() {
        String to = recipientField.getText().trim();
        String text = messageField.getText().trim();
        if (to.isEmpty() || text.isEmpty()) {
            setStatus("Укажите получателя и текст сообщения");
            return;
        }
        MessengerConnection c = connection;
        if (c == null || !c.isConnected()) {
            setStatus("Нет соединения — сообщение не отправлено");
            return;
        }
        c.send(to, text);
        appendHistory("[" + now() + "] " + currentLogin + " -> " + to + ": " + text + "\n");
        messageField.setText("");
        messageField.requestFocusInWindow();
    }

    /** Выход из аккаунта: LOGOUT + возврат на экран входа с новым соединением. */
    private void doLogout() {
        stopListRefresh();
        onlineModel.clear();
        currentLogin = null;
        MessengerConnection c = connection;
        connection = null;
        if (c != null) {
            c.logout();      // сервер отвечает OK и закрывает соединение
            c.disconnect("Вы вышли из аккаунта");
        }
        showCard(CARD_LOGIN);
        authStatusLabel.setText("Вы вышли. Войдите или зарегистрируйтесь.");
        startAutoConnect(); // восстанавливаем «чистое» соединение для нового входа
    }

    private void requestList() {
        MessengerConnection c = connection;
        if (c != null && c.isConnected()) {
            c.list();
        }
    }

    /** Периодический LIST — автоматическое обновление списка онлайн. */
    private void startListRefresh() {
        stopListRefresh();
        listRefreshTimer = new Timer(LIST_REFRESH_PERIOD_MS, e -> requestList());
        listRefreshTimer.setRepeats(true);
        listRefreshTimer.start();
    }

    private void stopListRefresh() {
        if (listRefreshTimer != null) {
            listRefreshTimer.stop();
            listRefreshTimer = null;
        }
    }

    /** Закрытие окна: отправить QUIT, закрыть сокет, завершить приложение. */
    private void onWindowClosing() {
        shuttingDown = true;
        stopListRefresh();
        MessengerConnection c = connection;
        if (c != null && c.isConnected()) {
            c.quit();
            c.disconnect("Завершение работы");
        }
        dispose();
        System.exit(0);
    }

    // =====================================================================
    // Утилиты (все вызываются только на EDT)
    // =====================================================================

    /** Парсит ответ "Онлайн (N): a, b, c" в модель списка. */
    private void updateOnlineList(String okArgs) {
        onlineModel.clear();
        int colon = okArgs.indexOf(':');
        if (colon < 0) return;
        String names = okArgs.substring(colon + 1).trim();
        if (names.isEmpty()) return;
        for (String n : names.split(",")) {
            String name = n.trim();
            if (!name.isEmpty()) onlineModel.addElement(name);
        }
    }

    /** Достаёт логин из приветственных ответов сервера. */
    private String extractLoginFromOk(String args) {
        int asIdx = args.lastIndexOf("как ");
        if (asIdx >= 0) return args.substring(asIdx + 4).trim();
        int hiIdx = args.indexOf("Привет, ");
        if (hiIdx >= 0) {
            String rest = args.substring(hiIdx + 8);
            int end = Math.max(rest.indexOf('!'), rest.indexOf(','));
            return end > 0 ? rest.substring(0, end).trim() : rest.trim();
        }
        return currentLogin;
    }

    private void appendHistory(String s) {
        historyArea.append(s);
        historyArea.setCaretPosition(historyArea.getDocument().getLength());
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =====================================================================
    // Точка входа. Приоритет параметров: CLI-аргументы > env > localhost:5000
    // =====================================================================

    public static void main(String[] args) {
        String host = "localhost";
        int port = 5000;

        // Переменные окружения перекрывают значения по умолчанию...
        String envHost = System.getenv("MESSENGER_HOST");
        String envPort = System.getenv("MESSENGER_PORT");
        if (envHost != null && !envHost.isBlank()) host = envHost.trim();
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {
                // некорректный порт в окружении — оставляем значение по умолчанию
            }
        }
        // ...а аргументы командной строки — переменные окружения
        if (args.length > 0 && !args[0].isBlank()) host = args[0].trim();
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.err.println("Некорректный порт '" + args[1] + "', использую " + port);
            }
        }

        final String fHost = host;
        final int fPort = port;
        SwingUtilities.invokeLater(() -> {
            ClientGUI gui = new ClientGUI(fHost, fPort);
            gui.setVisible(true);
            gui.startAutoConnect(); // авто-подключение сразу при запуске GUI
        });
    }
}
