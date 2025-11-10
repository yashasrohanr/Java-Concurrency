package org.example;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.regex.Pattern;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// ============= Core Enums and Data Structures =============

enum LogLevel {
    DEBUG(1), INFO(2), WARNING(3), ERROR(4), FATAL(5);

    private final int value;

    LogLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

class LogEntry {
    private final String message;
    private final LogLevel level;
    private final long timestamp;
    private final Map<String, Object> context;
    private final String threadName;

    public LogEntry(String message, LogLevel level, Map<String, Object> context) {
        this.message = message;
        this.level = level;
        this.timestamp = System.currentTimeMillis();
        this.context = new ConcurrentHashMap<>(context != null ? context : new HashMap<>());
        this.threadName = Thread.currentThread().getName();
    }

    // Getters
    public String getMessage() { return message; }
    public LogLevel getLevel() { return level; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getContext() { return new HashMap<>(context); }
    public String getThreadName() { return threadName; }
}

// ============= Formatters =============

interface LogFormatter {
    String format(LogEntry entry);
}

class SimpleFormatter implements LogFormatter {
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String format(LogEntry entry) {
        return String.format("[%s] %s - %s",
                formatTimestamp(entry.getTimestamp()),
                entry.getLevel().name(),
                entry.getMessage());
    }

    private String formatTimestamp(long timestamp) {
        return LocalDateTime.now().format(formatter);
    }
}

class DetailedFormatter implements LogFormatter {
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public String format(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(formatTimestamp(entry.getTimestamp())).append("] ");
        sb.append("[").append(entry.getLevel().name()).append("] ");
        sb.append("[").append(entry.getThreadName()).append("] ");
        sb.append(entry.getMessage());

        if (!entry.getContext().isEmpty()) {
            sb.append(" | Context: ").append(entry.getContext());
        }

        return sb.toString();
    }

    private String formatTimestamp(long timestamp) {
        return LocalDateTime.now().format(formatter);
    }
}

class JSONFormatter implements LogFormatter {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String format(LogEntry entry) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("timestamp", formatTimestamp(entry.getTimestamp()));
        logMap.put("level", entry.getLevel().name());
        logMap.put("thread", entry.getThreadName());
        logMap.put("message", entry.getMessage());
        logMap.put("context", entry.getContext());

        return gson.toJson(logMap);
    }

    private String formatTimestamp(long timestamp) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        return LocalDateTime.now().format(formatter);
    }
}

// ============= Filters =============

interface LogFilter {
    boolean filter(LogEntry entry);
}

class LevelFilter implements LogFilter {
    private final LogLevel minLevel;

    public LevelFilter(LogLevel minLevel) {
        this.minLevel = minLevel;
    }

    @Override
    public boolean filter(LogEntry entry) {
        return entry.getLevel().getValue() >= minLevel.getValue();
    }
}

class RegexFilter implements LogFilter {
    private final Pattern pattern;

    public RegexFilter(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean filter(LogEntry entry) {
        return pattern.matcher(entry.getMessage()).find();
    }
}

// ============= Log Handlers =============

abstract class LogHandler {
    protected LogLevel minLevel;
    protected LogFormatter formatter;
    protected LogHandler nextHandler;
    protected final List<LogFilter> filters = new CopyOnWriteArrayList<>();

    public LogHandler(LogLevel minLevel, LogFormatter formatter) {
        this.minLevel = minLevel;
        this.formatter = formatter;
    }

    public void setNextHandler(LogHandler next) {
        this.nextHandler = next;
    }

    public void addFilter(LogFilter filter) {
        filters.add(filter);
    }

    public void removeFilter(LogFilter filter) {
        filters.remove(filter);
    }

    public final void handle(LogEntry entry) {
        if (shouldHandle(entry)) {
            process(entry);
        }

        if (nextHandler != null) {
            nextHandler.handle(entry);
        }
    }

    protected boolean shouldHandle(LogEntry entry) {
        if (entry.getLevel().getValue() < minLevel.getValue()) {
            return false;
        }

        for (LogFilter filter : filters) {
            if (!filter.filter(entry)) {
                return false;
            }
        }

        return true;
    }

    protected abstract void process(LogEntry entry);
}

class ConsoleHandler extends LogHandler {
    public ConsoleHandler(LogLevel minLevel, LogFormatter formatter) {
        super(minLevel, formatter);
    }

    @Override
    protected void process(LogEntry entry) {
        String formatted = formatter.format(entry);
        if (entry.getLevel().getValue() >= LogLevel.ERROR.getValue()) {
            System.err.println(formatted);
        } else {
            System.out.println(formatted);
        }
    }
}

class FileHandler extends LogHandler {
    private final String filePath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private FileWriter writer;

    public FileHandler(LogLevel minLevel, LogFormatter formatter, String filePath) throws IOException {
        super(minLevel, formatter);
        this.filePath = filePath;
        initializeWriter();
    }

    private void initializeWriter() throws IOException {
        this.writer = new FileWriter(filePath, true);
    }

    @Override
    protected void process(LogEntry entry) {
        lock.writeLock().lock();
        try {
            String formatted = formatter.format(entry) + "\n";
            writer.write(formatted);
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (writer != null) {
                writer.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}

class NetworkHandler extends LogHandler {
    private final String host;
    private final int port;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public NetworkHandler(LogLevel minLevel, LogFormatter formatter, String host, int port) {
        super(minLevel, formatter);
        this.host = host;
        this.port = port;
    }

    @Override
    protected void process(LogEntry entry) {
        String formatted = formatter.format(entry);
        executor.submit(() -> sendToServer(formatted));
    }

    private void sendToServer(String logMessage) {
        try {
            // Simulated network send
            System.out.println(String.format("[NetworkHandler] Sending to %s:%d - %s",
                    host, port, logMessage));
        } catch (Exception e) {
            System.err.println("Network error: " + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}

// ============= Logger Configuration =============

class LoggerConfig {
    private LogLevel globalLevel = LogLevel.DEBUG;
    private final List<LogHandler> handlers = new CopyOnWriteArrayList<>();
    private final Map<String, Object> properties = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();

    public void setGlobalLevel(LogLevel level) {
        configLock.writeLock().lock();
        try {
            this.globalLevel = level;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public LogLevel getGlobalLevel() {
        configLock.readLock().lock();
        try {
            return globalLevel;
        } finally {
            configLock.readLock().unlock();
        }
    }

    public void addHandler(LogHandler handler) {
        configLock.writeLock().lock();
        try {
            handlers.add(handler);
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public void removeHandler(LogHandler handler) {
        configLock.writeLock().lock();
        try {
            handlers.remove(handler);
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public List<LogHandler> getHandlers() {
        configLock.readLock().lock();
        try {
            return new ArrayList<>(handlers);
        } finally {
            configLock.readLock().unlock();
        }
    }

    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }
}

// ============= Core Logger =============

class Logger {
    private static final Logger instance = new Logger();
    private LoggerConfig config = new LoggerConfig();
    private final ReentrantReadWriteLock configLock = new ReentrantReadWriteLock();
    private final ThreadLocal<Map<String, Object>> contextStack =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private Logger() {}

    public static Logger getInstance() {
        return instance;
    }

    public void configure(LoggerConfig newConfig) {
        configLock.writeLock().lock();
        try {
            this.config = newConfig;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void warning(String message) {
        log(LogLevel.WARNING, message);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public void fatal(String message) {
        log(LogLevel.FATAL, message);
    }

    private void log(LogLevel level, String message) {
        configLock.readLock().lock();
        try {
            if (level.getValue() < config.getGlobalLevel().getValue()) {
                return;
            }

            Map<String, Object> context = new HashMap<>(contextStack.get());
            LogEntry entry = new LogEntry(message, level, context);

            for (LogHandler handler : config.getHandlers()) {
                handler.handle(entry);
            }
        } finally {
            configLock.readLock().unlock();
        }
    }

    public Logger withContext(Map<String, Object> contextData) {
        contextStack.get().putAll(contextData);
        return this;
    }

    public void clearContext() {
        contextStack.remove();
    }
}

// ============= Usage Example =============

public class LoggingSystemDemo {
    public static void main(String[] args) throws IOException, InterruptedException {
        // Initialize Logger Configuration
        LoggerConfig config = new LoggerConfig();
        config.setGlobalLevel(LogLevel.DEBUG);

        // Setup Handlers
        ConsoleHandler consoleHandler = new ConsoleHandler(LogLevel.DEBUG, new DetailedFormatter());
        FileHandler fileHandler = new FileHandler(LogLevel.INFO, new JSONFormatter(), "logs.json");
        NetworkHandler networkHandler = new NetworkHandler(LogLevel.WARNING, new SimpleFormatter(),
                "logging-server.com", 8080);

        // Chain handlers
        consoleHandler.setNextHandler(fileHandler);
        fileHandler.setNextHandler(networkHandler);

        // Add filters
        consoleHandler.addFilter(new LevelFilter(LogLevel.DEBUG));

        config.addHandler(consoleHandler);

        // Configure Logger
        Logger logger = Logger.getInstance();
        logger.configure(config);

        // Demonstrate logging from multiple threads
        Thread t1 = new Thread(() -> {
            logger.withContext(Map.of("userId", "user123", "requestId", "req001"));
            logger.info("User logged in");
            logger.debug("Debug information");
        });

        Thread t2 = new Thread(() -> {
            logger.withContext(Map.of("userId", "user456", "requestId", "req002"));
            logger.warning("High memory usage detected");
            logger.error("Database connection failed");
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        logger.fatal("System shutting down");

        fileHandler.close();
        networkHandler.shutdown();
    }
}