package dk.itu.datasys;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class LogService {
    private final int logId;
    private final String message;
    private final int codeLine;
    private final String timeOfMessage;
    private final int column;
    private final LogType logType;

    public enum LogType {
        ERROR,
        WARNING,
        INFO
    }

    public enum LogServiceTimeFormat {
        EU, AMPM
    }

    private LogService(Builder builder) {
        this.logId = builder.logId;
        this.message = builder.message;
        this.codeLine = builder.codeLine;
        this.timeOfMessage = builder.timeOfMessage;
        this.column = builder.column;
        this.logType = builder.logType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getMessage() {
        return this.message;
    }

    public int getLogid() {
        return this.logId;
    }

    public int getCodeLine() {
        return this.codeLine;
    }

    public int getColumn() {
        return this.column;
    }

    public String getTimeOfMessage() {
        return this.timeOfMessage;
    }

    public LogType getLogType() {
        return this.logType;
    }

    @Override
    public String toString() {
        return String.format("Log [ID: %d | Time: %s | Line: %d | Message: %s]",
                this.logId, this.timeOfMessage, this.codeLine, this.message);
    }

    /* BUILDER PATTERN */

    public static class Builder {
        private int logId = 0;
        private int codeLine = 0;
        private int column = 0;
        private String message = null;
        private String timeOfMessage = null;
        private LogType logType = LogType.INFO;

        public Builder logId(int logId) {
            this.logId = logId;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder codeLine(int codeLine) {
            this.codeLine = codeLine;
            return this;
        }

        public Builder setColumn(int column) {
            this.column = column;
            return this;
        }

        public Builder setLogType(LogType logType) {
            this.logType = logType;
            return this;
        }

        public Builder LogServiceReportTime(LogServiceTimeFormat format) {
            LocalTime currentTime = LocalTime.now();

            if (format == LogServiceTimeFormat.AMPM) {
                DateTimeFormatter amPmFormatter = DateTimeFormatter.ofPattern("hh:mm:ss a");
                this.timeOfMessage = currentTime.format(amPmFormatter);
                return this;
            }

            DateTimeFormatter europeanFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            this.timeOfMessage = currentTime.format(europeanFormatter);
            return this;
        }

        public Builder LogServiceReportTime() {
            return this.LogServiceReportTime(LogServiceTimeFormat.EU);
        }

        public LogService build() {
            if (logId == 0) {
                throw new IllegalStateException("An argument must have an ID");
            }
            if (codeLine == 0) {
                System.err.printf("LogService id: %d No codeline is represented on LogService\n", logId);
            }
            if (logType == LogService.LogType.ERROR) {
                throw new IllegalStateException(
                        String.format("LogService id: %d is an ERROR. Terminating execution.", logId));
            }

            return new LogService(this);
        }

    }
}
