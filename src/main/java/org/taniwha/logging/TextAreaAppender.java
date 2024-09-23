package org.taniwha.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import javafx.application.Platform;
import javafx.scene.control.TextArea;

// Redirects the console logs to the GUI textbox
public class TextAreaAppender extends AppenderBase<ILoggingEvent> {

    private static TextArea textArea;

    public static void setTextArea(TextArea textArea) {
        TextAreaAppender.textArea = textArea;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (textArea != null) {
            Platform.runLater(() -> {
                StringBuilder logMessage = new StringBuilder();
                logMessage.append(eventObject.getTimeStamp())
                        .append(" [").append(eventObject.getThreadName()).append("] ")
                        .append(eventObject.getLevel()).append(" ")
                        .append(eventObject.getLoggerName()).append(" - ")
                        .append(eventObject.getFormattedMessage()).append("\n");
                // Append the exception's stack trace if there is one
                IThrowableProxy throwableProxy = eventObject.getThrowableProxy();
                if (throwableProxy != null)
                    logMessage.append(ThrowableProxyUtil.asString(throwableProxy)).append("\n");

                textArea.appendText(logMessage.toString());
            });
        }
    }
}
