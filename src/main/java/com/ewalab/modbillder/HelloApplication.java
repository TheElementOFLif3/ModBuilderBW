package com.ewalab.modbillder;

import javafx.application.Application;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class HelloApplication extends Application {
    private enum Lang {
        EN,
        RU,
        DE,
        SV,
        NL,
        FR,
        ES,
        SR
    }

    private final ModBuilderService service = new ModBuilderService();
    private Path workspaceDir;
    private SettingsStore settingsStore;

    private Stage stage;
    private final ObservableList<SourceEntry> sourceEntries = FXCollections.observableArrayList();

    private Label titleLabel;
    private Label languageLabel;
    private Label workspaceLabel;
    private Label workspaceValueLabel;
    private Label regionLabel;
    private Label gameRootLabel;
    private Label modsFolderLabel;
    private Label versionLabel;
    private Label outputDirLabel;
    private Label setupWindowTitleLabel;
    private Label installerNameLabel;
    private Label installerFilePreviewLabel;
    private Label installerIconLabel;
    private Label installerIconPreviewLabel;
    private Label installerIconPreviewHintLabel;
    private Label recommendedPathLabel;
    private Label recommendedPathValueLabel;
    private Label installerFilePreviewValueLabel;
    private Label sourceSectionLabel;
    private Label sourceCountLabel;
    private Label logSectionLabel;
    private Label dropHintLabel;

    private ComboBox<Lang> languageCombo;
    private ComboBox<Region> regionCombo;
    private TextField gameRootField;
    private TextField modsFolderField;
    private TextField versionField;
    private TextField outputDirField;
    private TextField setupWindowTitleField;
    private TextField installerNameField;
    private TextField installerIconField;
    private CheckBox createZipCheck;
    private CheckBox createInstallerExeCheck;
    private ListView<SourceEntry> sourceListView;
    private TextArea logArea;
    private ImageView installerIconPreviewView;

    private Button browseGameRootButton;
    private Button autoDetectButton;
    private Button browseOutputButton;
    private Button browseInstallerIconButton;
    private Button addFilesButton;
    private Button addFolderButton;
    private Button removeSelectedButton;
    private Button clearButton;
    private Button excludeSelectedButton;
    private Button includeSelectedButton;
    private Button buildButton;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        this.workspaceDir = service.ensureWorkspaceDir();
        this.settingsStore = new SettingsStore(workspaceDir);

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(buildScrollableContent());
        root.setBottom(buildBottomBar());

        applyDefaults();
        loadSettings();
        refreshTexts();
        updateDerivedPreviews();
        updateSourceCount();

        Scene scene = new Scene(root, 1100, 760);
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setTitle(windowTitle());
        stage.show();

        appendLog("Author: Blackwot");
        appendLog("License: Free to use");
        appendLog("Workspace folder: " + workspaceDir);
        if (!service.isWindows()) {
            appendLog("Windows auto-detect is available only when running on Windows.");
        }
    }

    @Override
    public void stop() {
        saveSettingsSafe();
    }

    private Pane buildTopBar() {
        titleLabel = new Label();
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        languageLabel = new Label();
        languageCombo = new ComboBox<>();
        languageCombo.getItems().setAll(Lang.EN, Lang.RU, Lang.DE, Lang.SV, Lang.NL, Lang.FR, Lang.ES, Lang.SR);
        languageCombo.setValue(Lang.EN);
        languageCombo.setPrefWidth(150);
        languageCombo.valueProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                refreshTexts();
                updateDerivedPreviews();
                saveSettingsSafe();
            }
        });
        languageCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Lang item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : languageDisplay(item));
            }
        });
        languageCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Lang item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : languageDisplay(item));
            }
        });

        regionCombo = new ComboBox<>();
        regionCombo.getItems().setAll(Region.values());
        regionCombo.setValue(Region.EU);
        regionCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Region item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : regionDisplay(item));
            }
        });
        regionCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Region item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : regionDisplay(item));
            }
        });

        HBox right = new HBox(8, languageLabel, languageCombo);
        right.setAlignment(Pos.CENTER_RIGHT);

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox top = new HBox(12, titleLabel, spacer, right);
        top.setPadding(new Insets(12, 14, 10, 14));
        top.setAlignment(Pos.CENTER_LEFT);
        return top;
    }

    private Node buildScrollableContent() {
        workspaceLabel = new Label();
        workspaceValueLabel = new Label();
        workspaceValueLabel.setWrapText(true);

        regionLabel = new Label();
        gameRootLabel = new Label();
        modsFolderLabel = new Label();
        versionLabel = new Label();
        outputDirLabel = new Label();
        setupWindowTitleLabel = new Label();
        installerNameLabel = new Label();
        installerFilePreviewLabel = new Label();
        installerIconLabel = new Label();
        installerIconPreviewLabel = new Label();
        installerIconPreviewHintLabel = new Label();
        recommendedPathLabel = new Label();
        recommendedPathValueLabel = new Label();
        recommendedPathValueLabel.setWrapText(true);
        recommendedPathValueLabel.setStyle("-fx-font-family: Menlo, Monaco, monospace;");
        installerFilePreviewValueLabel = new Label();
        installerFilePreviewValueLabel.setWrapText(true);
        installerFilePreviewValueLabel.setStyle("-fx-font-family: Menlo, Monaco, monospace;");
        installerIconPreviewHintLabel.setWrapText(true);

        gameRootField = new TextField();
        modsFolderField = new TextField();
        versionField = new TextField();
        outputDirField = new TextField();
        setupWindowTitleField = new TextField();
        installerNameField = new TextField();
        installerIconField = new TextField();

        browseGameRootButton = new Button();
        browseGameRootButton.setOnAction(e -> browseGameRoot());
        autoDetectButton = new Button();
        autoDetectButton.setOnAction(e -> autoDetectGameRoot());
        browseOutputButton = new Button();
        browseOutputButton.setOnAction(e -> browseOutputDir());
        browseInstallerIconButton = new Button();
        browseInstallerIconButton.setOnAction(e -> browseInstallerIcon());
        installerIconPreviewView = new ImageView();
        installerIconPreviewView.setFitWidth(56);
        installerIconPreviewView.setFitHeight(56);
        installerIconPreviewView.setPreserveRatio(true);
        installerIconPreviewView.setSmooth(true);

        createZipCheck = new CheckBox();
        createZipCheck.setSelected(true);
        createInstallerExeCheck = new CheckBox();
        createInstallerExeCheck.setSelected(false);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setMinWidth(170);
        c1.setPrefWidth(170);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setMinWidth(90);
        ColumnConstraints c4 = new ColumnConstraints();
        c4.setMinWidth(90);
        form.getColumnConstraints().addAll(c1, c2, c3, c4);

        int row = 0;
        form.add(workspaceLabel, 0, row);
        form.add(workspaceValueLabel, 1, row, 3, 1);
        row++;

        form.add(regionLabel, 0, row);
        form.add(regionCombo, 1, row);
        row++;

        form.add(gameRootLabel, 0, row);
        form.add(gameRootField, 1, row);
        form.add(browseGameRootButton, 2, row);
        form.add(autoDetectButton, 3, row);
        row++;

        form.add(modsFolderLabel, 0, row);
        form.add(modsFolderField, 1, row);
        row++;

        form.add(versionLabel, 0, row);
        form.add(versionField, 1, row);
        row++;

        form.add(outputDirLabel, 0, row);
        form.add(outputDirField, 1, row);
        form.add(browseOutputButton, 2, row);
        row++;

        form.add(recommendedPathLabel, 0, row);
        form.add(recommendedPathValueLabel, 1, row, 3, 1);
        row++;

        form.add(createZipCheck, 1, row, 3, 1);
        row++;

        form.add(createInstallerExeCheck, 1, row, 3, 1);
        row++;

        form.add(setupWindowTitleLabel, 0, row);
        form.add(setupWindowTitleField, 1, row, 3, 1);
        row++;

        form.add(installerNameLabel, 0, row);
        form.add(installerNameField, 1, row, 3, 1);
        row++;

        form.add(installerFilePreviewLabel, 0, row);
        form.add(installerFilePreviewValueLabel, 1, row, 3, 1);
        row++;

        form.add(installerIconLabel, 0, row);
        form.add(installerIconField, 1, row);
        form.add(browseInstallerIconButton, 2, row);
        row++;

        HBox installerIconPreviewBox = new HBox(12, installerIconPreviewView, installerIconPreviewHintLabel);
        installerIconPreviewBox.setAlignment(Pos.CENTER_LEFT);
        form.add(installerIconPreviewLabel, 0, row);
        form.add(installerIconPreviewBox, 1, row, 3, 1);

        setupReactivePreview();

        sourceSectionLabel = new Label();
        sourceSectionLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        sourceCountLabel = new Label();

        sourceListView = new ListView<>(sourceEntries);
        sourceListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        sourceListView.setPrefHeight(280);
        sourceListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SourceEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText((item.isIncluded() ? "[ON] " : "[OFF] ") + item.getPath());
                setStyle(item.isIncluded() ? "" : "-fx-opacity: 0.60;");
            }
        });
        sourceListView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                SourceEntry selected = sourceListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    selected.setIncluded(!selected.isIncluded());
                    sourceListView.refresh();
                    updateSourceCount();
                    saveSettingsSafe();
                }
            }
        });

        dropHintLabel = new Label();
        dropHintLabel.setWrapText(true);
        sourceListView.setPlaceholder(dropHintLabel);
        setupDragAndDrop(sourceListView);

        addFilesButton = new Button();
        addFilesButton.setOnAction(e -> addFilesDialog());
        addFolderButton = new Button();
        addFolderButton.setOnAction(e -> addFolderDialog());
        removeSelectedButton = new Button();
        removeSelectedButton.setOnAction(e -> removeSelected());
        clearButton = new Button();
        clearButton.setOnAction(e -> clearSources());
        excludeSelectedButton = new Button();
        excludeSelectedButton.setOnAction(e -> setSelectedIncluded(false));
        includeSelectedButton = new Button();
        includeSelectedButton.setOnAction(e -> setSelectedIncluded(true));

        FlowPane sourceActions = new FlowPane(8, 8,
                addFilesButton,
                addFolderButton,
                excludeSelectedButton,
                includeSelectedButton,
                removeSelectedButton,
                clearButton
        );
        sourceActions.setPrefWrapLength(900);

        HBox sourceHeader = new HBox(12, sourceSectionLabel, sourceCountLabel);
        sourceHeader.setAlignment(Pos.CENTER_LEFT);

        VBox sourceSection = new VBox(8, sourceHeader, sourceListView, sourceActions);

        logSectionLabel = new Label();
        logSectionLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(10);
        logArea.setPrefHeight(220);

        VBox content = new VBox(14,
                form,
                new Separator(),
                sourceSection,
                new Separator(),
                logSectionLabel,
                logArea
        );
        content.setPadding(new Insets(10, 14, 10, 14));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPannable(true);
        return scroll;
    }

    private Pane buildBottomBar() {
        buildButton = new Button();
        buildButton.setOnAction(e -> buildMods());
        buildButton.setDefaultButton(true);
        buildButton.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        HBox bar = new HBox(buildButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10, 14, 12, 14));
        return bar;
    }

    private void setupReactivePreview() {
        ChangeListener<Object> listener = (obs, oldV, newV) -> {
            updateDerivedPreviews();
            saveSettingsSafe();
        };
        regionCombo.valueProperty().addListener(listener);
        gameRootField.textProperty().addListener(listener);
        modsFolderField.textProperty().addListener(listener);
        versionField.textProperty().addListener(listener);
        outputDirField.textProperty().addListener((obs, oldV, newV) -> saveSettingsSafe());
        setupWindowTitleField.textProperty().addListener(listener);
        installerNameField.textProperty().addListener(listener);
        installerIconField.textProperty().addListener(listener);
        createZipCheck.selectedProperty().addListener((obs, oldV, newV) -> saveSettingsSafe());
        createInstallerExeCheck.selectedProperty().addListener((obs, oldV, newV) -> {
            updateInstallerOptionState();
            updateDerivedPreviews();
            saveSettingsSafe();
        });
        sourceEntries.addListener((ListChangeListener<SourceEntry>) c -> {
            updateSourceCount();
            saveSettingsSafe();
        });
    }

    private void setupDragAndDrop(ListView<SourceEntry> listView) {
        listView.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });

        listView.setOnDragDropped(event -> {
            boolean success = false;
            if (event.getDragboard().hasFiles()) {
                addSourcePaths(event.getDragboard().getFiles().stream().map(File::toPath).toList());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void applyDefaults() {
        regionCombo.setValue(Region.EU);
        modsFolderField.setText("mods");
        versionField.setText("2.1.1.2");
        outputDirField.setText(workspaceDir.toString());
        createZipCheck.setSelected(true);
        createInstallerExeCheck.setSelected(false);
        setupWindowTitleField.setText("");
        installerNameField.setText("");
        installerIconField.setText("");
        workspaceValueLabel.setText(workspaceDir.toString());
        updateInstallerOptionState();
    }

    private void loadSettings() {
        SettingsStore.SettingsData data = settingsStore.load();
        if (data == null) {
            return;
        }

        try {
            languageCombo.setValue(Lang.valueOf(data.language().toUpperCase(Locale.ROOT)));
        } catch (Exception ignored) {
            languageCombo.setValue(Lang.EN);
        }
        try {
            regionCombo.setValue(Region.valueOf(data.region().toUpperCase(Locale.ROOT)));
        } catch (Exception ignored) {
            regionCombo.setValue(Region.EU);
        }

        gameRootField.setText(data.gameRoot() == null ? "" : data.gameRoot());
        modsFolderField.setText(data.modsFolder() == null || data.modsFolder().isBlank() ? "mods" : data.modsFolder());
        versionField.setText(data.version() == null || data.version().isBlank() ? "2.1.1.2" : data.version());
        if (data.outputDir() != null && !data.outputDir().isBlank()) {
            outputDirField.setText(data.outputDir());
        }
        setupWindowTitleField.setText(data.setupWindowTitle() == null ? "" : data.setupWindowTitle());
        installerNameField.setText(data.installerName() == null ? "" : data.installerName());
        installerIconField.setText(data.installerIconPath() == null ? "" : data.installerIconPath());
        createZipCheck.setSelected(data.createZip());
        createInstallerExeCheck.setSelected(data.createInstallerExe());

        sourceEntries.setAll(data.sources() == null ? List.of() : data.sources());
        sortSources();
        updateInstallerOptionState();
    }

    private void saveSettingsSafe() {
        if (settingsStore == null || languageCombo == null || regionCombo == null) {
            return;
        }
        try {
            settingsStore.save(new SettingsStore.SettingsData(
                    languageCombo.getValue() == null ? Lang.EN.name() : languageCombo.getValue().name(),
                    regionCombo.getValue() == null ? Region.EU.name() : regionCombo.getValue().name(),
                    gameRootField == null ? "" : gameRootField.getText(),
                    modsFolderField == null ? "mods" : modsFolderField.getText(),
                    versionField == null ? "2.1.1.2" : versionField.getText(),
                    outputDirField == null ? workspaceDir.toString() : outputDirField.getText(),
                    installerNameField == null ? "" : installerNameField.getText(),
                    setupWindowTitleField == null ? "" : setupWindowTitleField.getText(),
                    installerIconField == null ? "" : installerIconField.getText(),
                    createZipCheck != null && createZipCheck.isSelected(),
                    createInstallerExeCheck != null && createInstallerExeCheck.isSelected(),
                    new ArrayList<>(sourceEntries)
            ));
        } catch (IOException e) {
            if (logArea != null) {
                appendLog("WARN: Failed to save settings: " + e.getMessage());
            }
        }
    }

    private void refreshTexts() {
        titleLabel.setText("Mod Builder BW");
        languageLabel.setText(tx("Language", "Язык", "Sprache", "Språk", "Taal", "Langue", "Idioma", "Jezik"));
        workspaceLabel.setText(tx("Workspace folder", "Рабочая папка", "Arbeitsordner", "Arbetsmapp", "Werkmap", "Dossier de travail", "Carpeta de trabajo", "Radni folder"));
        regionLabel.setText(tx("Region", "Регион", "Region", "Region", "Regio", "Région", "Región", "Region"));
        gameRootLabel.setText(tx("Game root / install folder", "Папка игры / установки", "Spielordner / Installationsordner", "Spelmapp / installationsmapp", "Spelmap / installatiemap", "Dossier du jeu / d'installation", "Carpeta del juego / instalación", "Folder igre / instalacije"));
        modsFolderLabel.setText(tx("Mods folder name", "Папка mods", "Mods-Ordnername", "Mods-mappnamn", "Mods-mapnaam", "Nom du dossier mods", "Nombre de carpeta mods", "Naziv mods foldera"));
        versionLabel.setText(tx("Version folder", "Папка версии", "Versionsordner", "Versionsmapp", "Versiemap", "Dossier de version", "Carpeta de versión", "Folder verzije"));
        outputDirLabel.setText(tx("Output folder", "Выходная папка", "Ausgabeordner", "Utmatningsmapp", "Uitvoermap", "Dossier de sortie", "Carpeta de salida", "Izlazni folder"));
        setupWindowTitleLabel.setText(tx("Setup window title", "Заголовок окна установщика", "Fenstertitel des Setups", "Titel för installationsfönster", "Titel van setupvenster", "Titre de la fenêtre d'installation", "Título de la ventana del instalador", "Naslov setup prozora"));
        installerNameLabel.setText(tx("Installer file name", "Имя EXE файла", "Installer-Dateiname", "Installationsfilnamn", "Bestandsnaam van installer", "Nom du fichier installateur", "Nombre del archivo instalador", "Ime installer fajla"));
        installerFilePreviewLabel.setText(tx("Installer EXE preview", "Превью EXE файла", "Vorschau der EXE-Datei", "Förhandsvisning av EXE-fil", "Voorbeeld van EXE-bestand", "Aperçu du fichier EXE", "Vista previa del archivo EXE", "Preview EXE fajla"));
        installerIconLabel.setText(tx("Installer icon", "Иконка установщика", "Installer-Symbol", "Installationsikon", "Installatie-icoon", "Icône de l'installateur", "Icono del instalador", "Ikonica installera"));
        installerIconPreviewLabel.setText(tx("Icon preview", "Превью иконки", "Symbolvorschau", "Ikonförhandsvisning", "Icoonvoorbeeld", "Aperçu de l'icône", "Vista previa del icono", "Preview ikonice"));
        recommendedPathLabel.setText(tx("Recommended install path", "Рекомендуемый путь", "Empfohlener Installationspfad", "Rekommenderad installationssökväg", "Aanbevolen installatiepad", "Chemin d'installation recommandé", "Ruta de instalación recomendada", "Preporučena putanja instalacije"));
        sourceSectionLabel.setText(tx("Mods (drag & drop)", "Моды (drag & drop)", "Mods (Drag & Drop)", "Moddar (dra och släpp)", "Mods (slepen en neerzetten)", "Mods (glisser-déposer)", "Mods (arrastrar y soltar)", "Modovi (prevuci i pusti)"));
        logSectionLabel.setText(tx("Log", "Лог", "Protokoll", "Logg", "Log", "Journal", "Registro", "Log"));
        dropHintLabel.setText(tx(
                "Drop mod files/folders here\nor use buttons below",
                "Перетащите сюда файлы/папки модов\nили используйте кнопки ниже",
                "Mod-Dateien/-Ordner hier ablegen\noder unten die Schaltflächen verwenden",
                "Släpp moddfiler/mappar här\neller använd knapparna nedan",
                "Sleep modbestanden/-mappen hierheen\nof gebruik de knoppen hieronder",
                "Déposez ici les fichiers/dossiers de mods\nou utilisez les boutons ci-dessous",
                "Suelta aquí archivos/carpetas de mods\no usa los botones de abajo",
                "Prevuci ovde mod fajlove/foldere\nili koristi dugmad ispod"
        ));

        String browseText = tx("Browse", "Обзор", "Durchsuchen", "Bläddra", "Bladeren", "Parcourir", "Examinar", "Pregledaj");
        browseGameRootButton.setText(browseText);
        autoDetectButton.setText(tx("Auto Detect", "Авто поиск", "Auto-Erkennung", "Autoidentifiera", "Autom. detectie", "Détection auto", "Detección auto", "Auto detekcija"));
        browseOutputButton.setText(browseText);
        browseInstallerIconButton.setText(browseText);
        addFilesButton.setText(tx("Add Files", "Добавить файлы", "Dateien hinzufügen", "Lägg till filer", "Bestanden toevoegen", "Ajouter des fichiers", "Agregar archivos", "Dodaj fajlove"));
        addFolderButton.setText(tx("Add Folder", "Добавить папку", "Ordner hinzufügen", "Lägg till mapp", "Map toevoegen", "Ajouter un dossier", "Agregar folder", "Dodaj folder"));
        removeSelectedButton.setText(tx("Remove Selected", "Удалить выбранное", "Auswahl entfernen", "Ta bort valda", "Geselecteerde verwijderen", "Supprimer la sélection", "Quitar seleccionados", "Ukloni izabrano"));
        clearButton.setText(tx("Clear", "Очистить", "Leeren", "Rensa", "Wissen", "Vider", "Limpiar", "Očisti"));
        excludeSelectedButton.setText(tx("Exclude Selected", "Исключить", "Auswahl ausschließen", "Exkludera valda", "Geselecteerde uitsluiten", "Exclure la sélection", "Excluir seleccionados", "Isključi izabrane"));
        includeSelectedButton.setText(tx("Include Selected", "Включить", "Auswahl einschließen", "Inkludera valda", "Geselecteerde opnemen", "Inclure la sélection", "Incluir seleccionados", "Uključi izabrane"));
        buildButton.setText(tx("Build Mods", "Собрать моды", "Mods bauen", "Bygg moddar", "Mods bouwen", "Construire les mods", "Compilar mods", "Napravi modove"));
        createZipCheck.setText(tx("Create ZIP package", "Создать ZIP пакет", "ZIP-Paket erstellen", "Skapa ZIP-paket", "ZIP-pakket maken", "Créer un paquet ZIP", "Crear paquete ZIP", "Napravi ZIP paket"));
        createInstallerExeCheck.setText(tx("Export Windows EXE installer", "Экспорт Windows EXE установщика", "Windows-EXE-Installer exportieren", "Exportera Windows EXE-installationsprogram", "Windows EXE-installatieprogramma exporteren", "Exporter l'installateur EXE Windows", "Exportar instalador EXE de Windows", "Izvezi Windows EXE installer"));

        stage.setTitle(windowTitle());
        updateDerivedPreviews();
        sourceListView.refresh();
        updateSourceCount();
        regionCombo.requestLayout();
    }

    private String windowTitle() {
        return tx(
                "Mod Builder BW for World of Tanks",
                "Mod Builder BW для World of Tanks",
                "Mod Builder BW für World of Tanks",
                "Mod Builder BW för World of Tanks",
                "Mod Builder BW voor World of Tanks",
                "Mod Builder BW pour World of Tanks",
                "Mod Builder BW para World of Tanks",
                "Mod Builder BW za World of Tanks"
        );
    }

    private Lang currentLang() {
        return languageCombo == null || languageCombo.getValue() == null ? Lang.EN : languageCombo.getValue();
    }

    private String languageDisplay(Lang lang) {
        if (lang == null) {
            return "";
        }
        return switch (lang) {
            case EN -> "EN - English";
            case RU -> "RU - Русский";
            case DE -> "DE - Deutsch";
            case SV -> "SV - Svenska";
            case NL -> "NL - Nederlands";
            case FR -> "FR - Français";
            case ES -> "ES - Español";
            case SR -> "SR - Srpski";
        };
    }

    private String tx(String en, String ru, String de, String sv, String nl, String fr, String es, String sr) {
        return switch (currentLang()) {
            case EN -> en;
            case RU -> ru;
            case DE -> de;
            case SV -> sv;
            case NL -> nl;
            case FR -> fr;
            case ES -> es;
            case SR -> sr;
        };
    }

    private String regionDisplay(Region region) {
        if (region == null) {
            return "";
        }
        return switch (region) {
            case EU -> "EU";
            case NA -> "NA";
            case RU -> "RU";
            case CUSTOM -> "CUSTOM";
            case NONE -> tx(
                    "NONE (direct folder)",
                    "NONE (прямая папка)",
                    "NONE (direkter Ordner)",
                    "NONE (direkt mapp)",
                    "NONE (directe map)",
                    "NONE (dossier direct)",
                    "NONE (carpeta directa)",
                    "NONE (direktan folder)"
            );
        };
    }

    private void updateRecommendedPreview() {
        Region region = regionCombo == null || regionCombo.getValue() == null ? Region.EU : regionCombo.getValue();
        String preview = service.recommendedInstallPath(region,
                text(gameRootField),
                text(modsFolderField),
                text(versionField));
        if (recommendedPathValueLabel != null) {
            recommendedPathValueLabel.setText(preview);
        }
    }

    private void updateDerivedPreviews() {
        updateRecommendedPreview();
        updateInstallerFilePreview();
        updateInstallerIconPreview();
    }

    private void updateInstallerFilePreview() {
        if (installerFilePreviewValueLabel == null) {
            return;
        }
        Region region = regionCombo == null || regionCombo.getValue() == null ? Region.EU : regionCombo.getValue();
        installerFilePreviewValueLabel.setText(service.previewInstallerFileName(text(installerNameField), region, text(versionField)));
        if (setupWindowTitleField != null) {
            setupWindowTitleField.setPromptText(service.previewSetupWindowTitle("", region, text(versionField)));
        }
        if (installerNameField != null) {
            installerNameField.setPromptText(service.previewInstallerFileName("", region, text(versionField)));
        }
    }

    private void updateInstallerIconPreview() {
        if (installerIconPreviewView == null || installerIconPreviewHintLabel == null) {
            return;
        }
        String iconPathText = text(installerIconField);
        if (iconPathText.isBlank()) {
            installerIconPreviewView.setImage(null);
            installerIconPreviewHintLabel.setText(tx("No installer icon selected", "Иконка установщика не выбрана", "Kein Installer-Symbol ausgewählt", "Ingen installationsikon vald", "Geen installatie-icoon geselecteerd", "Aucune icône d'installateur sélectionnée", "Ningún icono de instalador seleccionado", "Nijedna ikonica installera nije izabrana"));
            return;
        }
        try {
            Path iconPath = Path.of(iconPathText);
            var buffered = service.readInstallerIconPreview(iconPath);
            if (buffered == null) {
                installerIconPreviewView.setImage(null);
                installerIconPreviewHintLabel.setText(tx("Preview unavailable for this icon", "Превью для этой иконки недоступно", "Vorschau für dieses Symbol nicht verfügbar", "Förhandsvisning är inte tillgänglig för den här ikonen", "Voorbeeld niet beschikbaar voor dit icoon", "Aperçu indisponible pour cette icône", "Vista previa no disponible para este icono", "Preview nije dostupan za ovu ikonicu"));
                return;
            }
            installerIconPreviewView.setImage(SwingFXUtils.toFXImage(buffered, null));
            installerIconPreviewHintLabel.setText(iconPath.getFileName().toString());
        } catch (Exception e) {
            installerIconPreviewView.setImage(null);
            installerIconPreviewHintLabel.setText(tx("Preview unavailable for this icon", "Превью для этой иконки недоступно", "Vorschau für dieses Symbol nicht verfügbar", "Förhandsvisning är inte tillgänglig för den här ikonen", "Voorbeeld niet beschikbaar voor dit icoon", "Aperçu indisponible pour cette icône", "Vista previa no disponible para este icono", "Preview nije dostupan za ovu ikonicu"));
        }
    }

    private void updateSourceCount() {
        if (sourceCountLabel == null) {
            return;
        }
        long included = sourceEntries.stream().filter(SourceEntry::isIncluded).count();
        sourceCountLabel.setText(
                tx("Total", "Всего", "Gesamt", "Totalt", "Totaal", "Total", "Total", "Ukupno")
                        + ": " + sourceEntries.size()
                        + " | "
                        + tx("Included", "В сборке", "Im Build", "Inkluderade", "In build", "Inclus", "Incluidos", "U buildu")
                        + ": " + included
        );
    }

    private void addFilesDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tx("Choose mod files", "Выберите файлы модов", "Mod-Dateien auswählen", "Välj moddfiler", "Kies modbestanden", "Choisir les fichiers de mods", "Elegir archivos de mods", "Izaberi mod fajlove"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            addSourcePaths(files.stream().map(File::toPath).toList());
        }
    }

    private void addFolderDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tx("Choose mod folder", "Выберите папку мода", "Mod-Ordner auswählen", "Välj moddmapp", "Kies modmap", "Choisir le dossier du mod", "Elegir carpeta del mod", "Izaberi mod folder"));
        File folder = chooser.showDialog(stage);
        if (folder != null) {
            addSourcePaths(List.of(folder.toPath()));
        }
    }

    private void addSourcePaths(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }

        LinkedHashSet<Path> existing = sourceEntries.stream()
                .map(SourceEntry::getPath)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        int added = 0;
        for (Path raw : paths) {
            if (raw == null) {
                continue;
            }
            Path path = raw.toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                appendLog("Skip missing path: " + path);
                continue;
            }
            if (!existing.add(path)) {
                appendLog("Skip duplicate: " + path);
                continue;
            }
            sourceEntries.add(new SourceEntry(path, true));
            appendLog("Added: " + path);
            added++;
        }

        if (added > 0) {
            sortSources();
            saveSettingsSafe();
        }
    }

    private void sortSources() {
        FXCollections.sort(sourceEntries, Comparator.comparing(e -> e.getPath().toString().toLowerCase(Locale.ROOT)));
    }

    private void removeSelected() {
        List<SourceEntry> selected = new ArrayList<>(sourceListView.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        sourceEntries.removeAll(selected);
        appendLog((currentLang() == Lang.RU ? "Удалено элементов: " : "Removed items: ") + selected.size());
        saveSettingsSafe();
    }

    private void clearSources() {
        if (sourceEntries.isEmpty()) {
            return;
        }
        sourceEntries.clear();
        appendLog(currentLang() == Lang.RU ? "Список модов очищен." : "Mod list cleared.");
        saveSettingsSafe();
    }

    private void setSelectedIncluded(boolean included) {
        List<SourceEntry> selected = new ArrayList<>(sourceListView.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        for (SourceEntry entry : selected) {
            entry.setIncluded(included);
        }
        sourceListView.refresh();
        updateSourceCount();
        appendLog((included ? (currentLang() == Lang.RU ? "Включено элементов: " : "Included items: ")
                : (currentLang() == Lang.RU ? "Исключено элементов: " : "Excluded items: ")) + selected.size());
        saveSettingsSafe();
    }

    private void browseGameRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tx("Choose game root / install folder", "Выберите папку игры / установки", "Spielordner / Installationsordner wählen", "Välj spelmapp / installationsmapp", "Kies spelmap / installatiemap", "Choisir le dossier du jeu / d'installation", "Elegir carpeta del juego / instalación", "Izaberi folder igre / instalacije"));
        File current = toExistingDir(gameRootField.getText());
        if (current != null) {
            chooser.setInitialDirectory(current);
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            gameRootField.setText(chosen.getAbsolutePath());
            appendLog("Game root/install folder: " + chosen.getAbsolutePath());
        }
    }

    private void browseOutputDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(tx("Choose output folder", "Выберите выходную папку", "Ausgabeordner wählen", "Välj utmatningsmapp", "Kies uitvoermap", "Choisir le dossier de sortie", "Elegir carpeta de salida", "Izaberi izlazni folder"));
        File current = toExistingDir(outputDirField.getText());
        if (current != null) {
            chooser.setInitialDirectory(current);
        } else if (workspaceDir != null && Files.isDirectory(workspaceDir)) {
            chooser.setInitialDirectory(workspaceDir.toFile());
        }
        File chosen = chooser.showDialog(stage);
        if (chosen != null) {
            outputDirField.setText(chosen.getAbsolutePath());
            appendLog("Output folder: " + chosen.getAbsolutePath());
        }
    }

    private void browseInstallerIcon() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tx("Choose installer icon", "Выберите иконку установщика", "Installer-Symbol wählen", "Välj installationsikon", "Kies installatie-icoon", "Choisir l'icône de l'installateur", "Elegir icono del instalador", "Izaberi ikonicu installera"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                tx("Image / icon files", "Файлы изображений / иконок", "Bild-/Symboldateien", "Bild-/ikonfiler", "Afbeeldings-/icoonbestanden", "Fichiers image / icône", "Archivos de imagen / icono", "Fajlovi slika / ikonica"),
                "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.ico", "*.icns"
        ));
        File current = toExistingFile(installerIconField.getText());
        if (current != null) {
            chooser.setInitialDirectory(current.getParentFile());
            chooser.setInitialFileName(current.getName());
        }
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            installerIconField.setText(chosen.getAbsolutePath());
            appendLog("Installer icon: " + chosen.getAbsolutePath());
        }
    }

    private void autoDetectGameRoot() {
        Region region = regionCombo.getValue();
        if (!service.isWindows()) {
            appendLog(currentLang() == Lang.RU
                    ? "Авто поиск World_of_Tanks доступен только при запуске на Windows."
                    : "Auto-detect World_of_Tanks is available only when running on Windows.");
            return;
        }
        if (region == Region.NONE) {
            appendLog(currentLang() == Lang.RU
                    ? "Для NONE выберите папку установки вручную."
                    : "For NONE, choose the install folder manually.");
            return;
        }

        Optional<Path> found = service.detectWindowsWotRootForRegion(region == null ? Region.EU : region);
        if (found.isPresent()) {
            gameRootField.setText(found.get().toString());
            appendLog("Detected World_of_Tanks: " + found.get());
        } else {
            appendLog(currentLang() == Lang.RU
                    ? "Папка World_of_Tanks не найдена на дисках C/D/E/F."
                    : "No World_of_Tanks folder found on C/D/E/F.");
        }
    }

    private void buildMods() {
        Region region = regionCombo.getValue() == null ? Region.EU : regionCombo.getValue();
        List<Path> included = sourceEntries.stream()
                .filter(SourceEntry::isIncluded)
                .map(SourceEntry::getPath)
                .toList();

        if (included.isEmpty()) {
            showError(currentLang() == Lang.RU
                    ? "Нет модов для сборки (список пуст или все выключены)."
                    : "No mods to build (list is empty or all items are excluded).");
            return;
        }
        if (region == Region.NONE && text(gameRootField).isBlank()) {
            showError(currentLang() == Lang.RU
                    ? "Для региона NONE выберите папку установки."
                    : "For region NONE, choose an install folder.");
            return;
        }

        Path outputDir;
        try {
            outputDir = Path.of(text(outputDirField));
        } catch (Exception e) {
            showError(currentLang() == Lang.RU ? "Неверная выходная папка." : "Invalid output folder.");
            return;
        }

        appendLog("Source mod items: " + included.size());
        for (Path p : included) {
            appendLog("  - " + p);
        }
        appendLog("Recommended install path: " + service.recommendedInstallPath(region, text(gameRootField), text(modsFolderField), text(versionField)));

        Path installerIconPath = null;
        String installerIconText = text(installerIconField);
        if (!installerIconText.isBlank()) {
            try {
                installerIconPath = Path.of(installerIconText);
            } catch (Exception e) {
                showError(tx("Invalid installer icon file.", "Неверный файл иконки установщика.", "Ungültige Installer-Symboldatei.", "Ogiltig installationsikonfil.", "Ongeldig installatie-icoonbestand.", "Fichier d'icône d'installateur invalide.", "Archivo de icono de instalador no válido.", "Nevažeći fajl ikonice installera."));
                return;
            }
        }

        BuildRequest request = new BuildRequest(
                included,
                outputDir,
                region,
                blankToNull(text(gameRootField)),
                text(modsFolderField),
                text(versionField),
                text(installerNameField),
                text(setupWindowTitleField),
                installerIconPath,
                createZipCheck.isSelected(),
                createInstallerExeCheck.isSelected()
        );

        buildButton.setDisable(true);
        try {
            BuildResult result = service.build(request, this::appendLog);
            appendLog("Build finished. Copied items: " + result.copiedItems());
            if (result.zipFile() != null) {
                appendLog("ZIP ready: " + result.zipFile());
            }
            if (result.installerExeFile() != null) {
                appendLog("Installer EXE ready: " + result.installerExeFile());
            }
            showInfo(tx("Build completed", "Сборка завершена", "Build abgeschlossen", "Build klar", "Build voltooid", "Build terminé", "Compilación completada", "Build završen") + "\n" + result.buildFolder());
        } catch (IOException e) {
            appendLog("ERROR: " + e.getMessage());
            showError(e.getMessage());
        } finally {
            buildButton.setDisable(false);
            saveSettingsSafe();
        }
    }

    private void appendLog(String message) {
        if (logArea == null || message == null || message.isBlank()) {
            return;
        }
        String localized = localizeRuntimeMessage(message);
        if (!logArea.getText().isEmpty()) {
            logArea.appendText(System.lineSeparator());
        }
        logArea.appendText(localized);
        logArea.positionCaret(logArea.getLength());
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle(tx("Error", "Ошибка", "Fehler", "Fel", "Fout", "Erreur", "Error", "Greška"));
        alert.setHeaderText(null);
        alert.setContentText(localizeRuntimeMessage(message));
        alert.showAndWait();
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(tx("Done", "Готово", "Fertig", "Klar", "Gereed", "Terminé", "Listo", "Gotovo"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private String localizeRuntimeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String msg = message;

        msg = replacePrefix(msg, "WARN: Failed to save settings: ", tx(
                "WARN: Failed to save settings: ",
                "ПРЕДУПРЕЖДЕНИЕ: Не удалось сохранить настройки: ",
                "WARNUNG: Einstellungen konnten nicht gespeichert werden: ",
                "VARNING: Kunde inte spara inställningar: ",
                "WAARSCHUWING: Instellingen konden niet worden opgeslagen: ",
                "AVERTISSEMENT : Impossible d'enregistrer les paramètres : ",
                "ADVERTENCIA: No se pudieron guardar los ajustes: ",
                "UPOZORENJE: Čuvanje podešavanja nije uspelo: "
        ));
        msg = replacePrefix(msg, "Skip missing path: ", tx("Skip missing path: ", "Пропуск (путь не найден): ", "Pfad fehlt, übersprungen: ", "Hoppar över saknad sökväg: ", "Ontbrekend pad overgeslagen: ", "Chemin manquant ignoré : ", "Ruta faltante omitida: ", "Preskačem nedostajuću putanju: "));
        msg = replacePrefix(msg, "Skip duplicate: ", tx("Skip duplicate: ", "Пропуск (дубликат): ", "Duplikat übersprungen: ", "Hoppar över dubblett: ", "Dubbel item overgeslagen: ", "Doublon ignoré : ", "Duplicado omitido: ", "Preskačem duplikat: "));
        msg = replacePrefix(msg, "Added: ", tx("Added: ", "Добавлено: ", "Hinzugefügt: ", "Tillagd: ", "Toegevoegd: ", "Ajouté : ", "Agregado: ", "Dodato: "));
        msg = replacePrefix(msg, "Removed items: ", tx("Removed items: ", "Удалено элементов: ", "Entfernte Elemente: ", "Borttagna objekt: ", "Verwijderde items: ", "Éléments supprimés : ", "Elementos eliminados: ", "Uklonjeno stavki: "));
        msg = replaceExact(msg, "Mod list cleared.", tx("Mod list cleared.", "Список модов очищен.", "Mod-Liste geleert.", "Moddlistan rensad.", "Modlijst gewist.", "Liste des mods vidée.", "Lista de mods limpiada.", "Lista modova očišćena."));
        msg = replacePrefix(msg, "Included items: ", tx("Included items: ", "Включено элементов: ", "Eingeschlossene Elemente: ", "Inkluderade objekt: ", "Opgenomen items: ", "Éléments inclus : ", "Elementos incluidos: ", "Uključeno stavki: "));
        msg = replacePrefix(msg, "Excluded items: ", tx("Excluded items: ", "Исключено элементов: ", "Ausgeschlossene Elemente: ", "Exkluderade objekt: ", "Uitgesloten items: ", "Éléments exclus : ", "Elementos excluidos: ", "Isključeno stavki: "));
        msg = replacePrefix(msg, "Game root/install folder: ", tx("Game root/install folder: ", "Папка игры/установки: ", "Spielordner/Installationsordner: ", "Spelmapp/installationsmapp: ", "Spelmap/installatiemap: ", "Dossier du jeu/installation : ", "Carpeta del juego/instalación: ", "Folder igre/instalacije: "));
        msg = replacePrefix(msg, "Output folder: ", tx("Output folder: ", "Выходная папка: ", "Ausgabeordner: ", "Utmatningsmapp: ", "Uitvoermap: ", "Dossier de sortie : ", "Carpeta de salida: ", "Izlazni folder: "));
        msg = replacePrefix(msg, "Installer icon: ", tx("Installer icon: ", "Иконка установщика: ", "Installer-Symbol: ", "Installationsikon: ", "Installatie-icoon: ", "Icône de l'installateur : ", "Icono del instalador: ", "Ikonica installera: "));
        msg = replacePrefix(msg, "Installer icon file not found: ", tx("Installer icon file not found: ", "Файл иконки установщика не найден: ", "Installer-Symboldatei nicht gefunden: ", "Installationsikonfil hittades inte: ", "Installatie-icoonbestand niet gevonden: ", "Fichier d'icône de l'installateur introuvable : ", "Archivo de icono del instalador no encontrado: ", "Fajl ikonice installera nije pronađen: "));
        msg = replacePrefix(msg, "Unsupported installer icon format: ", tx("Unsupported installer icon format: ", "Неподдерживаемый формат иконки установщика: ", "Nicht unterstütztes Installer-Symbolformat: ", "Formatet för installationsikon stöds inte: ", "Niet-ondersteund installatie-icoonformaat: ", "Format d'icône d'installateur non pris en charge : ", "Formato de icono del instalador no compatible: ", "Nepodržan format ikonice installera: "));
        msg = replaceExact(msg, "Auto-detect World_of_Tanks is available only when running on Windows.", tx(
                "Auto-detect World_of_Tanks is available only when running on Windows.",
                "Авто поиск World_of_Tanks доступен только при запуске на Windows.",
                "Auto-Erkennung von World_of_Tanks ist nur unter Windows verfügbar.",
                "Autoidentifiering av World_of_Tanks är bara tillgänglig på Windows.",
                "Automatische detectie van World_of_Tanks is alleen beschikbaar op Windows.",
                "La détection automatique de World_of_Tanks est disponible uniquement sous Windows.",
                "La detección automática de World_of_Tanks solo está disponible en Windows.",
                "Auto detekcija World_of_Tanks dostupna je samo na Windows-u."
        ));
        msg = replaceExact(msg, "For NONE, choose the install folder manually.", tx(
                "For NONE, choose the install folder manually.",
                "Для NONE выберите папку установки вручную.",
                "Für NONE den Installationsordner manuell wählen.",
                "För NONE, välj installationsmappen manuellt.",
                "Kies voor NONE handmatig de installatiemap.",
                "Pour NONE, choisissez le dossier d'installation manuellement.",
                "Para NONE, elige manualmente la carpeta de instalación.",
                "Za NONE ručno izaberi folder instalacije."
        ));
        msg = replacePrefix(msg, "Detected World_of_Tanks: ", tx("Detected World_of_Tanks: ", "Найден World_of_Tanks: ", "World_of_Tanks gefunden: ", "World_of_Tanks hittad: ", "World_of_Tanks gevonden: ", "World_of_Tanks détecté : ", "World_of_Tanks detectado: ", "Detektovan World_of_Tanks: "));
        msg = replaceExact(msg, "No World_of_Tanks folder found on C/D/E/F.", tx(
                "No World_of_Tanks folder found on C/D/E/F.",
                "Папка World_of_Tanks не найдена на дисках C/D/E/F.",
                "Kein World_of_Tanks-Ordner auf C/D/E/F gefunden.",
                "Ingen World_of_Tanks-mapp hittades på C/D/E/F.",
                "Geen World_of_Tanks-map gevonden op C/D/E/F.",
                "Aucun dossier World_of_Tanks trouvé sur C/D/E/F.",
                "No se encontró carpeta World_of_Tanks en C/D/E/F.",
                "Nije pronađen World_of_Tanks folder na C/D/E/F."
        ));
        msg = replaceExact(msg, "No mods to build (list is empty or all items are excluded).", tx(
                "No mods to build (list is empty or all items are excluded).",
                "Нет модов для сборки (список пуст или все выключены).",
                "Keine Mods zum Bauen (Liste leer oder alles ausgeschlossen).",
                "Inga moddar att bygga (listan är tom eller allt är exkluderat).",
                "Geen mods om te bouwen (lijst is leeg of alles is uitgesloten).",
                "Aucun mod à construire (liste vide ou tout est exclu).",
                "No hay mods para compilar (lista vacía o todo excluido).",
                "Nema modova za build (lista je prazna ili je sve isključeno)."
        ));
        msg = replaceExact(msg, "For region NONE, choose an install folder.", tx(
                "For region NONE, choose an install folder.",
                "Для региона NONE выберите папку установки.",
                "Für Region NONE einen Installationsordner wählen.",
                "För region NONE, välj en installationsmapp.",
                "Kies voor regio NONE een installatiemap.",
                "Pour la région NONE, choisissez un dossier d'installation.",
                "Para la región NONE, elige una carpeta de instalación.",
                "Za region NONE izaberi folder instalacije."
        ));
        msg = replaceExact(msg, "Invalid output folder.", tx("Invalid output folder.", "Неверная выходная папка.", "Ungültiger Ausgabeordner.", "Ogiltig utmatningsmapp.", "Ongeldige uitvoermap.", "Dossier de sortie invalide.", "Carpeta de salida no válida.", "Nevažeći izlazni folder."));
        msg = replaceExact(msg, "Invalid installer icon file.", tx("Invalid installer icon file.", "Неверный файл иконки установщика.", "Ungültige Installer-Symboldatei.", "Ogiltig installationsikonfil.", "Ongeldig installatie-icoonbestand.", "Fichier d'icône d'installateur invalide.", "Archivo de icono de instalador no válido.", "Nevažeći fajl ikonice installera."));
        msg = replacePrefix(msg, "Source mod items: ", tx("Source mod items: ", "Исходных модов: ", "Quell-Mod-Elemente: ", "Källmodsobjekt: ", "Bron-moditems: ", "Éléments de mods source : ", "Elementos de mods fuente: ", "Izvornih mod stavki: "));
        msg = replacePrefix(msg, "Recommended install path: ", tx("Recommended install path: ", "Рекомендуемый путь установки: ", "Empfohlener Installationspfad: ", "Rekommenderad installationssökväg: ", "Aanbevolen installatiepad: ", "Chemin d'installation recommandé : ", "Ruta de instalación recomendada: ", "Preporučena putanja instalacije: "));
        msg = replacePrefix(msg, "Build finished. Copied items: ", tx("Build finished. Copied items: ", "Сборка завершена. Скопировано элементов: ", "Build fertig. Kopierte Elemente: ", "Build klar. Kopierade objekt: ", "Build voltooid. Gekopieerde items: ", "Build terminé. Éléments copiés : ", "Build completado. Elementos copiados: ", "Build završen. Kopirano stavki: "));
        msg = replacePrefix(msg, "ZIP ready: ", tx("ZIP ready: ", "ZIP готов: ", "ZIP bereit: ", "ZIP klar: ", "ZIP gereed: ", "ZIP prêt : ", "ZIP listo: ", "ZIP spreman: "));
        msg = replacePrefix(msg, "Installer EXE ready: ", tx("Installer EXE ready: ", "EXE установщик готов: ", "Installer-EXE bereit: ", "Installations-EXE klar: ", "Installer-EXE gereed: ", "Installateur EXE prêt : ", "Instalador EXE listo: ", "Installer EXE spreman: "));
        msg = replacePrefix(msg, "ERROR: ", tx("ERROR: ", "ОШИБКА: ", "FEHLER: ", "FEL: ", "FOUT: ", "ERREUR : ", "ERROR: ", "GREŠKA: "));

        msg = replaceExact(msg, "No source mods selected.", tx("No source mods selected.", "Моды не выбраны.", "Keine Quell-Mods ausgewählt.", "Inga källmoddar valda.", "Geen bron-mods geselecteerd.", "Aucun mod source sélectionné.", "No se seleccionaron mods fuente.", "Nijedan izvorni mod nije izabran."));
        msg = replaceExact(msg, "Set Game root / install folder before exporting Windows EXE installer.", tx(
                "Set Game root / install folder before exporting Windows EXE installer.",
                "Укажите папку игры / установки перед экспортом Windows EXE установщика.",
                "Vor dem Export des Windows-EXE-Installers Spiel-/Installationsordner festlegen.",
                "Ange spel-/installationsmapp innan export av Windows EXE-installation.",
                "Stel spel-/installatiemap in voordat je Windows EXE-installer exporteert.",
                "Définissez le dossier du jeu / d'installation avant d'exporter l'installateur EXE Windows.",
                "Define la carpeta del juego / instalación antes de exportar el instalador EXE de Windows.",
                "Podesi folder igre / instalacije pre izvoza Windows EXE installera."
        ));
        msg = replaceExact(msg, "makensis (NSIS) not found on Windows. Reinstall Mod Builder BW (bundled NSIS) or install NSIS manually.", tx(
                "makensis (NSIS) not found on Windows. Reinstall Mod Builder BW (bundled NSIS) or install NSIS manually.",
                "makensis (NSIS) не найден в Windows. Переустановите Mod Builder BW (с встроенным NSIS) или установите NSIS вручную.",
                "makensis (NSIS) unter Windows nicht gefunden. Mod Builder BW neu installieren (mit gebündeltem NSIS) oder NSIS manuell installieren.",
                "makensis (NSIS) hittades inte i Windows. Installera om Mod Builder BW (med inkluderad NSIS) eller installera NSIS manuellt.",
                "makensis (NSIS) niet gevonden op Windows. Installeer Mod Builder BW opnieuw (met ingebouwde NSIS) of installeer NSIS handmatig.",
                "makensis (NSIS) introuvable sous Windows. Réinstallez Mod Builder BW (NSIS inclus) ou installez NSIS manuellement.",
                "makensis (NSIS) no se encontró en Windows. Reinstala Mod Builder BW (NSIS incluido) o instala NSIS manualmente.",
                "makensis (NSIS) nije pronađen na Windows-u. Reinstaliraj Mod Builder BW (sa ugrađenim NSIS) ili ručno instaliraj NSIS."
        ));
        msg = replaceExact(msg, "Docker is required for Windows EXE cross-build on this OS. Start Docker/OrbStack and try again.", tx(
                "Docker is required for Windows EXE cross-build on this OS. Start Docker/OrbStack and try again.",
                "Для cross-build Windows EXE на этой ОС нужен Docker. Запустите Docker/OrbStack и повторите.",
                "Für den Windows-EXE-Cross-Build auf diesem OS wird Docker benötigt. Docker/OrbStack starten und erneut versuchen.",
                "Docker krävs för Windows EXE cross-build på detta OS. Starta Docker/OrbStack och försök igen.",
                "Docker is vereist voor Windows EXE cross-build op dit OS. Start Docker/OrbStack en probeer opnieuw.",
                "Docker est requis pour le cross-build EXE Windows sur cet OS. Démarrez Docker/OrbStack et réessayez.",
                "Se requiere Docker para cross-build de EXE de Windows en este SO. Inicia Docker/OrbStack e inténtalo de nuevo.",
                "Docker je potreban za Windows EXE cross-build na ovom OS-u. Pokreni Docker/OrbStack i pokušaj ponovo."
        ));
        msg = replacePrefix(msg, "Build folder created: ", tx("Build folder created: ", "Креирана папка сборки: ", "Build-Ordner erstellt: ", "Build-mapp skapad: ", "Build-map aangemaakt: ", "Dossier de build créé : ", "Carpeta de build creada: ", "Kreiran build folder: "));
        msg = replacePrefix(msg, "Copied folder: ", tx("Copied folder: ", "Скопирована папка: ", "Ordner kopiert: ", "Mapp kopierad: ", "Map gekopieerd: ", "Dossier copié : ", "Carpeta copiada: ", "Kopiran folder: "));
        msg = replacePrefix(msg, "Copied file: ", tx("Copied file: ", "Скопирован файл: ", "Datei kopiert: ", "Fil kopierad: ", "Bestand gekopieerd: ", "Fichier copié : ", "Archivo copiado: ", "Kopiran fajl: "));
        msg = replacePrefix(msg, "ZIP package created: ", tx("ZIP package created: ", "ZIP пакет создан: ", "ZIP-Paket erstellt: ", "ZIP-paket skapat: ", "ZIP-pakket aangemaakt: ", "Paquet ZIP créé : ", "Paquete ZIP creado: ", "ZIP paket kreiran: "));
        msg = replacePrefix(msg, "Windows EXE installer created: ", tx("Windows EXE installer created: ", "Windows EXE установщик создан: ", "Windows EXE-Installer erstellt: ", "Windows EXE-installation skapad: ", "Windows EXE-installer gemaakt: ", "Installateur EXE Windows créé : ", "Instalador EXE de Windows creado: ", "Windows EXE installer kreiran: "));
        msg = replacePrefix(msg, "Building Windows EXE using local makensis (NSIS): ", tx("Building Windows EXE using local makensis (NSIS): ", "Сборка Windows EXE через локальный makensis (NSIS): ", "Windows EXE wird mit lokalem makensis (NSIS) gebaut: ", "Bygger Windows EXE med lokal makensis (NSIS): ", "Windows EXE bouwen met lokale makensis (NSIS): ", "Construction de l'EXE Windows avec makensis (NSIS) local : ", "Compilando EXE de Windows con makensis local (NSIS): ", "Pravim Windows EXE preko lokalnog makensis (NSIS): "));
        msg = replaceExact(msg, "Building Windows EXE using Docker NSIS cross-build...", tx(
                "Building Windows EXE using Docker NSIS cross-build...",
                "Сборка Windows EXE через Docker NSIS cross-build...",
                "Windows EXE wird per Docker NSIS Cross-Build erstellt...",
                "Bygger Windows EXE via Docker NSIS cross-build...",
                "Windows EXE wordt gebouwd via Docker NSIS cross-build...",
                "Construction de l'EXE Windows via Docker NSIS cross-build...",
                "Compilando EXE de Windows mediante Docker NSIS cross-build...",
                "Pravim Windows EXE preko Docker NSIS cross-build..."
        ));
        msg = replacePrefix(msg, "Command interrupted: ", tx("Command interrupted: ", "Команда прервана: ", "Befehl unterbrochen: ", "Kommando avbröts: ", "Opdracht onderbroken: ", "Commande interrompue : ", "Comando interrumpido: ", "Komanda prekinuta: "));
        msg = replacePrefix(msg, "Command failed (exit ", tx("Command failed (exit ", "Команда завершилась ошибкой (код ", "Befehl fehlgeschlagen (Exit ", "Kommandot misslyckades (exit ", "Opdracht mislukt (exit ", "Commande échouée (code ", "Comando falló (salida ", "Komanda nije uspela (exit "));

        return msg;
    }

    private String replacePrefix(String message, String prefix, String replacementPrefix) {
        return message.startsWith(prefix) ? replacementPrefix + message.substring(prefix.length()) : message;
    }

    private String replaceExact(String message, String exact, String replacement) {
        return message.equals(exact) ? replacement : message;
    }

    private void updateInstallerOptionState() {
        boolean enabled = createInstallerExeCheck != null && createInstallerExeCheck.isSelected();
        if (setupWindowTitleField != null) {
            setupWindowTitleField.setDisable(!enabled);
        }
        if (installerNameField != null) {
            installerNameField.setDisable(!enabled);
        }
        if (installerIconField != null) {
            installerIconField.setDisable(!enabled);
        }
        if (browseInstallerIconButton != null) {
            browseInstallerIconButton.setDisable(!enabled);
        }
        if (installerFilePreviewValueLabel != null) {
            installerFilePreviewValueLabel.setOpacity(enabled ? 1.0 : 0.7);
        }
        if (installerIconPreviewView != null) {
            installerIconPreviewView.setOpacity(enabled ? 1.0 : 0.7);
        }
        if (installerIconPreviewHintLabel != null) {
            installerIconPreviewHintLabel.setOpacity(enabled ? 1.0 : 0.7);
        }
    }

    private File toExistingDir(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        File dir = new File(value);
        return dir.isDirectory() ? dir : null;
    }

    private File toExistingFile(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        File file = new File(value);
        return file.isFile() ? file : null;
    }

    private String text(TextField field) {
        return field == null ? "" : field.getText().trim();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
