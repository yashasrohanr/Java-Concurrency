package org.example;

// ==================== Log Level Enum ====================
enum LogLevel {
    DEBUG(1),
    INFO(2),
    WARNING(3),
    ERROR(4),
    FATAL(5);

    private final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}

// ==================== Log Message ====================
class LogMessage {
    private final String message;
    private final LogLevel level;
    private final long timestamp;
    private final String threadName;
    private final String className;

    private LogMessage(Builder builder) {
        this.message = builder.message;
        this.level = builder.level;
        this.timestamp = builder.timestamp;
        this.threadName = builder.threadName;
        this.className = builder.className;
    }

    public String getMessage() { return message; }
    public LogLevel getLevel() { return level; }
    public long getTimestamp() { return timestamp; }
    public String getThreadName() { return threadName; }
    public String getClassName() { return className; }

    public static class Builder {
        private String message;
        private LogLevel level;
        private long timestamp;
        private String threadName;
        private String className;

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public LogMessage build() {
            return new LogMessage(this);
        }
    }
}

// ==================== Log Config ====================
class LogConfig {
    private final LogLevel minLogLevel;
    private final boolean includeTimestamp;
    private final boolean includeThreadName;
    private final boolean includeClassName;

    private LogConfig(Builder builder) {
        this.minLogLevel = builder.minLogLevel;
        this.includeTimestamp = builder.includeTimestamp;
        this.includeThreadName = builder.includeThreadName;
        this.includeClassName = builder.includeClassName;
    }

    public LogLevel getMinLogLevel() { return minLogLevel; }
    public boolean isIncludeTimestamp() { return includeTimestamp; }
    public boolean isIncludeThreadName() { return includeThreadName; }
    public boolean isIncludeClassName() { return includeClassName; }

    public static class Builder {
        private LogLevel minLogLevel = LogLevel.DEBUG;
        private boolean includeTimestamp = true;
        private boolean includeThreadName = false;
        private boolean includeClassName = false;

        public Builder minLogLevel(LogLevel level) {
            this.minLogLevel = level;
            return this;
        }

        public Builder includeTimestamp(boolean include) {
            this.includeTimestamp = include;
            return this;
        }

        public Builder includeThreadName(boolean include) {
            this.includeThreadName = include;
            return this;
        }

        public Builder includeClassName(boolean include) {
            this.includeClassName = include;
            return this;
        }

        public LogConfig build() {
            return new LogConfig(this);
        }
    }
}

// ==================== Log Appender Interface ====================
interface LogAppender {
    void append(LogMessage message);
}

// ==================== Console Appender ====================
class ConsoleAppender implements LogAppender {
    private final LogConfig config;

    public ConsoleAppender(LogConfig config) {
        this.config = config;
    }

    @Override
    public void append(LogMessage message) {
        StringBuilder sb = new StringBuilder();

        if (config.isIncludeTimestamp()) {
            sb.append("[").append(new java.util.Date(message.getTimestamp())).append("] ");
        }

        sb.append("[").append(message.getLevel()).append("] ");

        if (config.isIncludeThreadName()) {
            sb.append("[").append(message.getThreadName()).append("] ");
        }

        if (config.isIncludeClassName()) {
            sb.append("[").append(message.getClassName()).append("] ");
        }

        sb.append(message.getMessage());

        System.out.println(sb.toString());
    }
}

// ==================== File Appender ====================
class FileAppender implements LogAppender {
    private final LogConfig config;
    private final String filePath;
    private final Object lock = new Object();

    public FileAppender(LogConfig config, String filePath) {
        this.config = config;
        this.filePath = filePath;
    }

    @Override
    public void append(LogMessage message) {
        StringBuilder sb = new StringBuilder();

        if (config.isIncludeTimestamp()) {
            sb.append("[").append(new java.util.Date(message.getTimestamp())).append("] ");
        }

        sb.append("[").append(message.getLevel()).append("] ");

        if (config.isIncludeThreadName()) {
            sb.append("[").append(message.getThreadName()).append("] ");
        }

        if (config.isIncludeClassName()) {
            sb.append("[").append(message.getClassName()).append("] ");
        }

        sb.append(message.getMessage()).append("\n");

        synchronized (lock) {
            try (java.io.FileWriter fw = new java.io.FileWriter(filePath, true)) {
                fw.write(sb.toString());
            } catch (java.io.IOException e) {
                System.err.println("Failed to write to log file: " + e.getMessage());
            }
        }
    }
}

// ==================== Abstract Log Handler (Chain of Responsibility) ====================
abstract class LogHandler {
    protected LogHandler nextHandler;
    protected LogLevel level;
    protected LogAppender appender;

    public LogHandler(LogLevel level, LogAppender appender) {
        this.level = level;
        this.appender = appender;
    }

    public void setNextHandler(LogHandler handler) {
        this.nextHandler = handler;
    }

    public void handle(LogMessage message) {
        if (message.getLevel().getPriority() >= this.level.getPriority()) {
            write(message);
        }

        if (nextHandler != null) {
            nextHandler.handle(message);
        }
    }

    protected void write(LogMessage message) {
        appender.append(message);
    }
}

// ==================== Concrete Log Handlers ====================
class DebugLogHandler extends LogHandler {
    public DebugLogHandler(LogAppender appender) {
        super(LogLevel.DEBUG, appender);
    }
}

class InfoLogHandler extends LogHandler {
    public InfoLogHandler(LogAppender appender) {
        super(LogLevel.INFO, appender);
    }
}

class ErrorLogHandler extends LogHandler {
    public ErrorLogHandler(LogAppender appender) {
        super(LogLevel.ERROR, appender);
    }
}

// ==================== Logger (Singleton) ====================
class Logger {
    private static volatile Logger instance;
    private static final Object lock = new Object();

    private LogHandler handlerChain;
    private LogConfig config;

    private Logger() {
        // Default configuration
        this.config = new LogConfig.Builder().build();
    }

    public static Logger getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new Logger();
                }
            }
        }
        return instance;
    }

    public void setConfig(LogConfig config) {
        synchronized (lock) {
            this.config = config;
        }
    }

    public void setHandlerChain(LogHandler chain) {
        synchronized (lock) {
            this.handlerChain = chain;
        }
    }

    private void log(LogLevel level, String message) {
        if (handlerChain == null) {
            throw new IllegalStateException("Logger not configured. Set handler chain first.");
        }

        if (level.getPriority() < config.getMinLogLevel().getPriority()) {
            return;
        }

        LogMessage logMessage = new LogMessage.Builder()
                .message(message)
                .level(level)
                .timestamp(System.currentTimeMillis())
                .threadName(Thread.currentThread().getName())
                .className(getCallerClassName())
                .build();

        synchronized (lock) {
            handlerChain.handle(logMessage);
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

    private String getCallerClassName() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // Skip: getStackTrace, getCallerClassName, log, debug/info/etc methods
        if (stackTrace.length > 4) {
            return stackTrace[4].getClassName();
        }
        return "Unknown";
    }
}

// ==================== Demo Usage ====================
public class LoggingSystemDemo {
    public static void main(String[] args) {
        // Configure logger
        LogConfig config = new LogConfig.Builder()
                .minLogLevel(LogLevel.DEBUG)
                .includeTimestamp(true)
                .includeThreadName(true)
                .includeClassName(true)
                .build();

        // Create appenders
        Logger logger = getLogger(config);
        logger.info("Application started successfully");
        logger.warning("This is a warning");
        logger.error("An error occurred");
        logger.fatal("Critical system failure");

        // Test thread safety
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                logger.info("Thread 1 - Message " + i);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                logger.info("Thread 2 - Message " + i);
            }
        });

        t1.start();
        t2.start();
    }

    private static Logger getLogger(LogConfig config) {
        LogAppender consoleAppender = new ConsoleAppender(config);
        LogAppender fileAppender = new FileAppender(config, "application.log");

        // Build chain of responsibility
        LogHandler debugHandler = new DebugLogHandler(consoleAppender);
        LogHandler infoHandler = new InfoLogHandler(consoleAppender);
        LogHandler errorHandler = new ErrorLogHandler(fileAppender);

        debugHandler.setNextHandler(infoHandler);
        infoHandler.setNextHandler(errorHandler);

        // Configure logger
        Logger logger = Logger.getInstance();
        logger.setConfig(config);
        logger.setHandlerChain(debugHandler);

        // Test logging
        logger.debug("This is a debug message");
        return logger;
    }
}