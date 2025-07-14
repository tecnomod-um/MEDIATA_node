package org.taniwha.view;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.taniwha.Application;
import org.taniwha.logging.TextAreaAppender;
import org.taniwha.util.ColorUtil;

import java.awt.MenuItem;
import java.awt.*;
import java.util.Objects;

public class Gui extends javafx.application.Application {

    private Stage primaryStage;
    private TextArea logArea;
    private TrayIcon trayIcon;
    private ProgressBar progressBar;
    private boolean isAppRunning = false;

    private static final String TITLE = "TANIWHA Node";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        Platform.setImplicitExit(false);
        this.primaryStage = primaryStage;
        primaryStage.setTitle(TITLE);
        primaryStage.getIcons().add(new javafx.scene.image.Image(Objects.requireNonNull(getClass().getResourceAsStream("/images/trayIcon.png"))));

        TextField portField = new TextField("8080");
        TextField nodeIpField = new TextField("localhost");
        TextField nameField = new TextField("SCUBA");
        TextField descField = new TextField("This is the description for the new node");

        String randomHexColor = ColorUtil.generateRandomColor();
        Color randomColor = Color.web(randomHexColor);
        ColorPicker colorPicker = new ColorPicker(randomColor);
        colorPicker.setMaxWidth(Double.MAX_VALUE);

        Label portLabel = createLabel("Port");
        Label nodeIpLabel = createLabel("URL");
        Label nameLabel = createLabel("Name");
        Label descLabel = createLabel("Description");
        Label colorLabel = createLabel("Color");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        TextAreaAppender.setTextArea(logArea);
        progressBar = new ProgressBar();
        progressBar.setVisible(false);

        Button submitButton = new Button("Connect");
        submitButton.setId("connect-button");
        submitButton.setOnAction(event -> {
            if (!isAppRunning) {
                String color = "#" + colorPicker.getValue().toString().substring(2, 8).toUpperCase();
                handleConnect(portField, nodeIpField, nameField, descField, color);
            }
        });

        Button clearButton = new Button("Clear Console");
        clearButton.setId("clear-console-button");

        clearButton.setOnAction(event -> logArea.clear());

        Button stopExitButton = new Button("Stop & Exit");
        stopExitButton.setId("stop-exit-button");
        stopExitButton.setOnAction(event -> {
            Application.stopSpringBootApp();
            Platform.exit();
            System.exit(0);
        });

        Button minimizeButton = new Button("Minimize to Tray");
        minimizeButton.setId("minimize-button");
        minimizeButton.setOnAction(event -> minimizeToTray());

        double buttonWidth = 150;
        submitButton.setPrefWidth(buttonWidth);
        stopExitButton.setPrefWidth(buttonWidth);
        clearButton.setPrefWidth(buttonWidth);
        minimizeButton.setPrefWidth(buttonWidth);

        // Grouping label and text fields
        HBox labelAndNodeIpField = new HBox(10, nodeIpLabel, nodeIpField);
        HBox labelAndPortField = new HBox(10, portLabel, portField);
        HBox labelAndNameField = new HBox(10, nameLabel, nameField);
        HBox labelAndColorPicker = new HBox(10, colorLabel, colorPicker);
        HBox labelAndDescField = new HBox(10, descLabel, descField);

        labelAndNodeIpField.setSpacing(0);
        labelAndPortField.setSpacing(0);
        labelAndNameField.setSpacing(0);
        labelAndColorPicker.setSpacing(0);
        labelAndDescField.setSpacing(0);

        HBox.setHgrow(nodeIpField, Priority.ALWAYS);
        HBox.setHgrow(portField, Priority.ALWAYS);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        HBox.setHgrow(colorPicker, Priority.ALWAYS);
        HBox.setHgrow(descField, Priority.ALWAYS);

        HBox.setHgrow(labelAndNodeIpField, Priority.ALWAYS);
        HBox.setHgrow(labelAndPortField, Priority.ALWAYS);
        HBox.setHgrow(labelAndNameField, Priority.ALWAYS);
        HBox.setHgrow(labelAndColorPicker, Priority.ALWAYS);
        HBox.setHgrow(labelAndDescField, Priority.ALWAYS);

        HBox row1 = new HBox(10, labelAndNodeIpField, labelAndPortField, minimizeButton);
        HBox row2 = new HBox(10, labelAndNameField, labelAndColorPicker, clearButton);
        HBox row3 = new HBox(10, labelAndDescField, submitButton, stopExitButton);

        HBox.setHgrow(row1, Priority.ALWAYS);
        HBox.setHgrow(row2, Priority.ALWAYS);
        HBox.setHgrow(row3, Priority.ALWAYS);

        row1.setPrefWidth(800);
        row2.setPrefWidth(800);
        row3.setPrefWidth(800);

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(0, 0, 10, 10));
        grid.setVgap(8);
        grid.setHgap(8);
        grid.setAlignment(Pos.CENTER);

        // Add rows to the grid
        grid.add(row1, 0, 0);
        grid.add(row2, 0, 1);
        grid.add(row3, 0, 2);

        GridPane.setHgrow(progressBar, Priority.ALWAYS);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        grid.add(progressBar, 0, 3, 3, 1);
        StackPane stackPane = new StackPane();
        stackPane.getChildren().addAll(logArea);
        GridPane.setVgrow(stackPane, Priority.ALWAYS);
        GridPane.setMargin(progressBar, new Insets(0, 10, 0, 0));
        grid.add(stackPane, 0, 4);

        Scene scene = new Scene(grid);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();

        primaryStage.setOnCloseRequest((WindowEvent event) -> {
            event.consume();
            minimizeToTray();
        });
    }

    private void handleConnect(TextField portField, TextField nodeIpField, TextField nameField, TextField descField, String color) {
        isAppRunning = true;
        progressBar.setVisible(true);
        Task<Void> task = new Task<Void>() {
            @Override
            protected Void call() {
                try {
                    String port = portField.getText();
                    String nodeIp = nodeIpField.getText();
                    String name = nameField.getText();
                    String desc = descField.getText();

                    log("Port: " + port);
                    log("Node IP: " + nodeIp);
                    log("Name: " + name);
                    log("Description: " + desc);
                    log("Color: " + color);

                    String[] args = new String[]{
                            "--PORT=" + port,
                            "--NODE_IP=" + nodeIp,
                            "--NAME=" + name,
                            "--DESC=" + desc,
                            "--COLOR=" + color
                    };
                    Application.launchSpringBootApp(args);
                } catch (Exception e) {
                    Platform.runLater(() -> log("Error starting the application: " + e.getMessage()));
                } finally {
                    Platform.runLater(() -> {
                        progressBar.setVisible(false);
                        isAppRunning = false;
                    });
                }
                return null;
            }
        };
        new Thread(task).start();
    }

    private Label createLabel(String text) {
        Label label = new Label(text);
        label.setFont(new Font("Arial", 14));
        label.setTextFill(Color.DARKSLATEGRAY);
        label.setStyle("-fx-background-color: #F0F0F0; -fx-padding: 5px;");
        label.setMinHeight(35);
        return label;
    }

    private void log(String message) {
        Platform.runLater(() -> logArea.appendText(message + "\n"));
    }

    private void restoreView() {
        if (primaryStage != null) {
            if (primaryStage.isIconified())
                primaryStage.setIconified(false);
            if (!primaryStage.isShowing())
                primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
        }
    }

    private void minimizeToTray() {
        if (SystemTray.isSupported()) {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                if (trayIcon == null) {
                    java.awt.Image image = Toolkit.getDefaultToolkit().getImage(getClass().getResource("/images/trayIcon16.png"));
                    PopupMenu popup = getPopupMenu();
                    trayIcon = new TrayIcon(image, TITLE, popup);
                    trayIcon.setImageAutoSize(true);
                    trayIcon.addActionListener(e -> Platform.runLater(this::restoreView));
                    tray.add(trayIcon);
                    log("Tray icon successfully initialized and displayed.");
                }
                primaryStage.hide();
                trayIcon.displayMessage(TITLE, "The application is minimized to the tray.", TrayIcon.MessageType.INFO);
            } catch (Exception e) {
                log("Error minimizing to tray: " + e.getMessage());
            }
        } else
            log("System tray not supported on this platform.");
    }

    private PopupMenu getPopupMenu() {
        PopupMenu popup = new PopupMenu();

        MenuItem restoreItem = new MenuItem("Restore");
        restoreItem.addActionListener(e -> Platform.runLater(this::restoreView));
        popup.add(restoreItem);
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> {
            Application.stopSpringBootApp();
            Platform.exit();
            System.exit(0);
        });
        popup.add(exitItem);
        return popup;
    }
}
