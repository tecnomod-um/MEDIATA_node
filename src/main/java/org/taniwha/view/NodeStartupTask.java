package org.taniwha.view;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.TextField;
import org.taniwha.Application;

import java.util.function.Consumer;

final class NodeStartupTask extends Task<Void> {
    private final TextField portField;
    private final TextField nodeIpField;
    private final TextField nameField;
    private final TextField descField;
    private final String color;
    private final Consumer<String> log;
    private final Runnable onFinished;

    NodeStartupTask(TextField portField, TextField nodeIpField, TextField nameField,
                    TextField descField, String color, Consumer<String> log, Runnable onFinished) {
        this.portField = portField;
        this.nodeIpField = nodeIpField;
        this.nameField = nameField;
        this.descField = descField;
        this.color = color;
        this.log = log;
        this.onFinished = onFinished;
    }

    @Override
    protected Void call() {
        try {
            String port = portField.getText();
            String nodeIp = nodeIpField.getText();
            String name = nameField.getText();
            String desc = descField.getText();

            log.accept("Port: " + port);
            log.accept("Node IP: " + nodeIp);
            log.accept("Name: " + name);
            log.accept("Description: " + desc);
            log.accept("Color: " + color);

            String[] args = new String[]{
                    "--PORT=" + port,
                    "--NODE_IP=" + nodeIp,
                    "--NAME=" + name,
                    "--DESC=" + desc,
                    "--COLOR=" + color
            };
            Application.launchSpringBootApp(args);
        } catch (Exception e) {
            Platform.runLater(() -> log.accept("Error starting the application: " + e.getMessage()));
        } finally {
            Platform.runLater(onFinished);
        }
        return null;
    }
}
