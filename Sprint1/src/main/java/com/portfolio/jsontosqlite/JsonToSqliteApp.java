package com.portfolio.jsontosqlite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public final class JsonToSqliteApp {
    private static final String VALUE_FIELD = "value";
    private static final String JSON_RECORDS_TABLE = "json_records";
    private static final String JSON_ARRAY_TABLES_TABLE = "json_array_tables";
    private static final String JSON_NODES_TABLE = "json_nodes";

    private final JFrame frame = new JFrame("JSON to SQLite");
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JLabel statusLabel = new JLabel("Choose a JSON file and a destination for the SQLite database.");
    private final JButton convertButton = new JButton("Convert");
    private final JButton inputBrowseButton = new JButton("Browse...");
    private final JButton outputBrowseButton = new JButton("Browse...");

    private JsonToSqliteApp() {
        buildWindow();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new JsonToSqliteApp().frame.setVisible(true));
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new java.awt.Dimension(620, 230));
        frame.setSize(720, 260);
        frame.setLocationRelativeTo(null);

        JPanel content = new JPanel(new BorderLayout(12, 16));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

        JPanel fileChoices = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 5, 6, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addFileRow(fileChoices, constraints, 0, "JSON file", inputField, inputBrowseButton);
        addFileRow(fileChoices, constraints, 1, "SQLite output", outputField, outputBrowseButton);

        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.add(statusLabel, BorderLayout.CENTER);
        actions.add(convertButton, BorderLayout.EAST);

        content.add(fileChoices, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        frame.setContentPane(content);

        inputBrowseButton.addActionListener(event -> chooseInput());
        outputBrowseButton.addActionListener(event -> chooseOutput());
        convertButton.addActionListener(event -> convert());
    }

    private void addFileRow(JPanel panel, GridBagConstraints constraints, int row,
                            String label, JTextField field, JButton browseButton) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        field.setEditable(false);
        panel.add(field, constraints);

        constraints.gridx = 2;
        constraints.weightx = 0;
        panel.add(browseButton, constraints);
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a JSON file");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            inputField.setText(chooser.getSelectedFile().getAbsolutePath());
            if (outputField.getText().isBlank()) {
                Path input = chooser.getSelectedFile().toPath();
                outputField.setText(input.resolveSibling(stripExtension(input.getFileName().toString()) + ".sqlite").toString());
            }
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose where to save the SQLite database");
        chooser.setSelectedFile(new java.io.File(outputField.getText().isBlank() ? "translated.sqlite" : outputField.getText()));
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite databases (*.sqlite, *.db)", "sqlite", "db"));
        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(ensureSqliteExtension(chooser.getSelectedFile().toPath()).toString());
        }
    }

    private void convert() {
        if (inputField.getText().isBlank() || outputField.getText().isBlank()) {
            showError("Choose both a JSON input file and an SQLite output location.");
            return;
        }

        Path input = Path.of(inputField.getText());
        Path output = ensureSqliteExtension(Path.of(outputField.getText()));
        if (!Files.isRegularFile(input) || !input.getFileName().toString().toLowerCase().endsWith(".json")) {
            showError("Select an existing .json file.");
            return;
        }
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            showError("The output database must be different from the input file.");
            return;
        }
        if (!confirmReplaceExistingOutput(output)) {
            return;
        }

        setBusy(true);
        statusLabel.setText("Reading JSON and writing database...");
        ProgressWindow progressWindow = new ProgressWindow(frame, input.getFileName().toString());
        progressWindow.setVisible(true);
        new SwingWorker<ImportResult, Void>() {
            @Override
            protected ImportResult doInBackground() throws Exception {
                return importJson(input, output, progressWindow::scheduleUpdate);
            }

            @Override
            protected void done() {
                setBusy(false);
                progressWindow.dispose();
                try {
                    ImportResult result = get();
                    statusLabel.setText("Conversion complete: " + result.recordCount() + " records written.");
                    JOptionPane.showMessageDialog(frame,
                            "Created SQLite database:\n" + output.toAbsolutePath()
                                    + "\n\nRecords written: " + result.recordCount()
                                    + "\nNested array rows: " + result.relatedRowCount(),
                            "Conversion complete",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Conversion interrupted.");
                    showError("The conversion was interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    statusLabel.setText("Conversion failed.");
                    showError("Could not convert the file: "
                            + (cause == null ? exception.getMessage() : cause.getMessage()));
                }
            }
        }.execute();
    }

    private boolean confirmReplaceExistingOutput(Path output) {
        return !Files.exists(output) || JOptionPane.showConfirmDialog(
                frame,
                "The selected database already exists. Its imported JSON tables will be replaced; other tables will be kept. Continue?",
                "Replace imported data?",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private static ImportResult importJson(Path input, Path output, Consumer<ProgressUpdate> progressUpdates)
            throws IOException, SQLException {
        JsonElement root;
        try (var reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        }
        ProgressTracker progress = new ProgressTracker(collectUniqueKeys(root), progressUpdates);
        progress.publish();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + output.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    dropPreviousImport(connection, statement);
                }
                ImportResult result = writeRecords(connection, root, progress);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("Failed while importing JSON nodes.", exception);
            }
        }
    }

    private static Set<String> collectUniqueKeys(JsonElement element) {
        Set<String> keys = new LinkedHashSet<>();
        collectUniqueKeys(element, keys);
        return keys;
    }

    private static void collectUniqueKeys(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) {
                keys.add(member.getKey());
                collectUniqueKeys(member.getValue(), keys);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectUniqueKeys(item, keys);
            }
        }
    }

    private static void markRecordKeys(JsonElement element, ProgressTracker progress) {
        if (!element.isJsonObject()) {
            return;
        }
        for (var member : element.getAsJsonObject().entrySet()) {
            progress.markKey(member.getKey());
            if (member.getValue().isJsonObject()) {
                markRecordKeys(member.getValue(), progress);
            }
        }
    }

    private static ImportResult writeRecords(Connection connection, JsonElement root, ProgressTracker progress)
            throws SQLException {
        JsonElement recordSource = root;
        String recordPath = "$";
        if (root.isJsonObject() && root.getAsJsonObject().size() == 1) {
            var wrapper = root.getAsJsonObject().entrySet().iterator().next();
            if (wrapper.getValue().isJsonArray()) {
                recordSource = wrapper.getValue();
                recordPath += "." + escapeKey(wrapper.getKey()) + "[]";
                progress.markKey(wrapper.getKey());
            }
        }

        List<JsonElement> records = new ArrayList<>();
        if (recordSource.isJsonArray()) {
            for (JsonElement element : recordSource.getAsJsonArray()) {
                records.add(element);
            }
        } else {
            records.add(recordSource);
        }

        RecordTable rootTable = new RecordTable(JSON_RECORDS_TABLE, recordPath, null);
        Map<String, RecordTable> childTables = new LinkedHashMap<>();
        for (JsonElement jsonRecord : records) {
            collectFields(jsonRecord, rootTable, "", childTables);
        }
        for (JsonElement jsonRecord : records) {
            collectRecordValues(jsonRecord, rootTable, "", childTables);
        }
        removeConstantFields(rootTable);
        for (RecordTable table : childTables.values()) {
            removeConstantFields(table);
        }
        assignInternalColumns(rootTable, false);
        for (RecordTable table : childTables.values()) {
            assignInternalColumns(table, true);
        }

        createRecordTable(connection, rootTable);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE json_array_tables (path TEXT PRIMARY KEY, table_name TEXT NOT NULL, parent_table TEXT NOT NULL)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO json_array_tables (path, table_name, parent_table) VALUES (?, ?, ?)")) {
            for (RecordTable table : childTables.values()) {
                createRecordTable(connection, table);
                insert.setString(1, table.path);
                insert.setString(2, table.name);
                insert.setString(3, table.parentName);
                insert.addBatch();
            }
            if (!childTables.isEmpty()) {
                insert.executeBatch();
            }
        }

        try (ImportContext context = new ImportContext(connection, childTables, progress)) {
            long rowCount = 0;
            for (int index = 0; index < records.size(); index++) {
                rowCount += insertRecord(context, rootTable, records.get(index), null, index);
            }
            return new ImportResult(records.size(), rowCount - records.size());
        }
    }

    private static void collectFields(JsonElement element, RecordTable table, String fieldPath,
                                     Map<String, RecordTable> childTables) {
        if (element.isJsonObject()) {
            collectObjectFields(element, table, fieldPath, childTables);
            return;
        }
        if (element.isJsonArray()) {
            collectArrayFields(element, table, fieldPath, childTables);
            return;
        }
        table.fields.add(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath);
    }

    private static void collectObjectFields(JsonElement element, RecordTable table, String fieldPath,
                                            Map<String, RecordTable> childTables) {
        if (element.getAsJsonObject().size() == 0) {
            table.fields.add(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath);
            return;
        }
        for (var member : element.getAsJsonObject().entrySet()) {
            String childPath = joinFieldPath(fieldPath, member.getKey());
            collectFields(member.getValue(), table, childPath, childTables);
        }
    }

    private static void collectArrayFields(JsonElement element, RecordTable table, String fieldPath,
                                           Map<String, RecordTable> childTables) {
        String arrayPath = table.path + (fieldPath.isEmpty() ? "" : "." + fieldPath) + "[]";
        RecordTable childTable = childTables.computeIfAbsent(arrayPath, path -> {
            String tableName = createArrayTableName(arrayPath, childTables.values());
            return new RecordTable(tableName, arrayPath, table.name);
        });
        for (JsonElement item : element.getAsJsonArray()) {
            collectFields(item, childTable, "", childTables);
        }
    }

    private static void assignInternalColumns(RecordTable table, boolean childTable) {
        if (table.fields.isEmpty()) {
            table.fields.add(VALUE_FIELD);
        }
        Set<String> usedNames = new LinkedHashSet<>(table.fields);
        table.rowIdColumn = uniqueInternalName(usedNames, "__row_id");
        usedNames.add(table.rowIdColumn);
        if (childTable) {
            table.parentIdColumn = uniqueInternalName(usedNames, "__parent_id");
            usedNames.add(table.parentIdColumn);
            table.arrayIndexColumn = uniqueInternalName(usedNames, "__array_index");
        }
    }

    private static void collectRecordValues(JsonElement element, RecordTable table, String fieldPath,
                                            Map<String, RecordTable> childTables) {
        Map<String, String> values = new LinkedHashMap<>();
        flattenFields(element, "", values);
        table.rows.add(values);
        collectNestedArrayValues(element, table, fieldPath, childTables);
    }

    private static void collectNestedArrayValues(JsonElement element, RecordTable parentTable, String fieldPath,
                                                 Map<String, RecordTable> childTables) {
        if (element.isJsonObject()) {
            for (var member : element.getAsJsonObject().entrySet()) {
                collectNestedArrayValues(member.getValue(), parentTable,
                        joinFieldPath(fieldPath, member.getKey()), childTables);
            }
            return;
        }
        if (!element.isJsonArray()) {
            return;
        }

        String arrayPath = parentTable.path + (fieldPath.isEmpty() ? "" : "." + fieldPath) + "[]";
        RecordTable childTable = childTables.get(arrayPath);
        if (childTable == null) {
            return;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            collectRecordValues(item, childTable, "", childTables);
        }
    }

    private static void removeConstantFields(RecordTable table) {
        if (table.rows.size() < 2) {
            return;
        }
        table.fields.removeIf(field -> {
            Set<String> uniqueValues = new LinkedHashSet<>();
            for (Map<String, String> row : table.rows) {
                uniqueValues.add(row.get(field));
                if (uniqueValues.size() > 1) {
                    return false;
                }
            }
            return uniqueValues.size() == 1;
        });
    }

    private static String uniqueInternalName(Set<String> usedNames, String preferredName) {
        StringBuilder name = new StringBuilder(preferredName);
        while (usedNames.contains(name.toString())) {
            name.insert(0, '_');
        }
        return name.toString();
    }

    private static String createArrayTableName(String arrayPath, Iterable<RecordTable> existingTables) {
        String readablePath = arrayPath.replace("$", "")
                .replace("[]", "")
                .replace("\\.", " dot ")
                .replace("\\[", " ")
                .replace("\\]", " ")
                .replace("\\\\", " ")
                .replace('.', ' ');
        StringBuilder name = new StringBuilder("json_");
        for (int index = 0; index < readablePath.length(); index++) {
            char character = readablePath.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                if (!name.isEmpty() && name.charAt(name.length() - 1) != '_') {
                    name.append('_');
                }
                continue;
            }
            if (Character.isUpperCase(character) && !name.isEmpty()
                    && name.charAt(name.length() - 1) != '_'
                    && Character.isLowerCase(name.charAt(name.length() - 1))) {
                name.append('_');
            }
            name.append(Character.toLowerCase(character));
        }

        String baseName = name.toString().replaceAll("_+", "_");
        if (baseName.endsWith("_")) {
            baseName = baseName.substring(0, baseName.length() - 1);
        }
        Set<String> usedNames = new LinkedHashSet<>(List.of(
            JSON_RECORDS_TABLE, JSON_ARRAY_TABLES_TABLE, JSON_NODES_TABLE));
        for (RecordTable table : existingTables) {
            usedNames.add(table.name);
        }
        String uniqueName = baseName;
        int suffix = 2;
        while (usedNames.contains(uniqueName)) {
            uniqueName = baseName + "_" + suffix++;
        }
        return uniqueName;
    }

    private static void createRecordTable(Connection connection, RecordTable table) throws SQLException {
        StringBuilder createSql = new StringBuilder("CREATE TABLE ")
                .append(quoteIdentifier(table.name)).append(" (")
                .append(quoteIdentifier(table.rowIdColumn)).append(" INTEGER PRIMARY KEY");
        if (table.parentIdColumn != null) {
            createSql.append(", ").append(quoteIdentifier(table.parentIdColumn)).append(" INTEGER NOT NULL")
                    .append(", ").append(quoteIdentifier(table.arrayIndexColumn)).append(" INTEGER NOT NULL");
        }
        for (String field : table.fields) {
            createSql.append(", ").append(quoteIdentifier(field)).append(" TEXT");
        }
        createSql.append(')');
        try (Statement statement = connection.createStatement()) {
            statement.execute(createSql.toString());
        }
    }

    private static long insertRecord(ImportContext context, RecordTable table, JsonElement element,
                                     Long parentId, int arrayIndex) throws SQLException {
        markRecordKeys(element, context.progress);
        Map<String, String> values = new LinkedHashMap<>();
        flattenFields(element, "", values);
        PreparedStatement insert = context.insertStatement(table);
        int parameter = 1;
        if (table.parentIdColumn != null) {
            insert.setLong(parameter++, parentId);
            insert.setInt(parameter++, arrayIndex);
        }
        for (String field : table.fields) {
            insert.setString(parameter++, values.get(field));
        }
        insert.executeUpdate();

        long rowId;
        try (ResultSet generatedKeys = insert.getGeneratedKeys()) {
            if (!generatedKeys.next()) {
                throw new SQLException("SQLite did not return an inserted row ID.");
            }
            rowId = generatedKeys.getLong(1);
        }
        return 1 + insertChildArrays(context, table, element, rowId, "");
    }

    private static long insertChildArrays(ImportContext context, RecordTable parentTable,
                                          JsonElement element, long parentId, String fieldPath) throws SQLException {
        if (element.isJsonObject()) {
            long rows = 0;
            for (var member : element.getAsJsonObject().entrySet()) {
                String childPath = joinFieldPath(fieldPath, member.getKey());
                rows += insertChildArrays(context, parentTable, member.getValue(), parentId, childPath);
            }
            return rows;
        }
        if (!element.isJsonArray()) {
            return 0;
        }

        String arrayPath = parentTable.path + (fieldPath.isEmpty() ? "" : "." + fieldPath) + "[]";
        RecordTable childTable = context.childTables.get(arrayPath);
        if (childTable == null) {
            throw new SQLException("No SQLite table was created for JSON array " + arrayPath);
        }
        long rows = 0;
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            rows += insertRecord(context, childTable, array.get(index), parentId, index);
        }
        return rows;
    }

    private static void flattenFields(JsonElement element, String fieldPath, Map<String, String> values) {
        if (element.isJsonObject()) {
            if (element.getAsJsonObject().size() == 0) {
                values.put(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath, element.toString());
                return;
            }
            for (var member : element.getAsJsonObject().entrySet()) {
                String childPath = joinFieldPath(fieldPath, member.getKey());
                flattenFields(member.getValue(), childPath, values);
            }
        } else if (!element.isJsonArray()) {
            values.put(fieldPath.isEmpty() ? VALUE_FIELD : fieldPath, scalarValue(element));
        }
    }

    private static String scalarValue(JsonElement element) {
        if (element.isJsonNull()) {
            return null;
        }
        if (element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static void dropPreviousImport(Connection connection, Statement statement) throws SQLException {
        boolean importMetadataExists;
        try (ResultSet tables = connection.getMetaData().getTables(null, null, JSON_ARRAY_TABLES_TABLE, null)) {
            importMetadataExists = tables.next();
        }
        if (importMetadataExists) {
            List<String> importedTableNames = new ArrayList<>();
            try (Statement query = connection.createStatement();
                 ResultSet importedTables = query.executeQuery("SELECT table_name FROM json_array_tables")) {
                while (importedTables.next()) {
                    String tableName = importedTables.getString(1);
                    if (!tableName.matches("json_[a-z0-9_]+")
                            || tableName.equals(JSON_RECORDS_TABLE)
                            || tableName.equals(JSON_ARRAY_TABLES_TABLE)
                            || tableName.equals(JSON_NODES_TABLE)) {
                        throw new SQLException("Invalid generated table name in json_array_tables.");
                    }
                    importedTableNames.add(tableName);
                }
            }
            dropGeneratedArrayTables(statement, importedTableNames);
        }
        statement.execute("DROP TABLE IF EXISTS json_array_tables");
        statement.execute("DROP TABLE IF EXISTS json_records");
        statement.execute("DROP TABLE IF EXISTS json_nodes");
    }

    @SuppressWarnings("java:S2077")
    private static void dropGeneratedArrayTables(Statement statement, List<String> tableNames) throws SQLException {
        for (String tableName : tableNames) {
            statement.addBatch("DROP TABLE IF EXISTS " + quoteIdentifier(tableName));
        }
        if (!tableNames.isEmpty()) {
            statement.executeBatch();
        }
    }

    private static String escapeKey(String key) {
        if (key.isEmpty()) {
            return "\\0";
        }
        return key.replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("_", "__");
    }

    private static String joinFieldPath(String parentPath, String key) {
        String escapedKey = escapeKey(key);
        return parentPath.isEmpty() ? escapedKey : parentPath + "_" + escapedKey;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static final class RecordTable {
        private final String name;
        private final String path;
        private final String parentName;
        private final Set<String> fields = new LinkedHashSet<>();
        private final List<Map<String, String>> rows = new ArrayList<>();
        private String rowIdColumn;
        private String parentIdColumn;
        private String arrayIndexColumn;

        private RecordTable(String name, String path, String parentName) {
            this.name = name;
            this.path = path;
            this.parentName = parentName;
        }
    }

    private static final class ImportContext implements AutoCloseable {
        private final Connection connection;
        private final Map<String, RecordTable> childTables;
        private final ProgressTracker progress;
        private final Map<String, PreparedStatement> insertStatements = new LinkedHashMap<>();

        private ImportContext(Connection connection, Map<String, RecordTable> childTables, ProgressTracker progress) {
            this.connection = connection;
            this.childTables = childTables;
            this.progress = progress;
        }

        private PreparedStatement insertStatement(RecordTable table) throws SQLException {
            PreparedStatement existing = insertStatements.get(table.name);
            if (existing != null) {
                return existing;
            }

            List<String> columns = new ArrayList<>();
            if (table.parentIdColumn != null) {
                columns.add(table.parentIdColumn);
                columns.add(table.arrayIndexColumn);
            }
            columns.addAll(table.fields);

            StringBuilder sql = new StringBuilder("INSERT INTO ")
                    .append(quoteIdentifier(table.name)).append(" (");
            StringBuilder placeholders = new StringBuilder();
            for (int index = 0; index < columns.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
                sql.append(quoteIdentifier(columns.get(index)));
                placeholders.append('?');
            }
            sql.append(") VALUES (").append(placeholders).append(')');
            PreparedStatement insert = connection.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS);
            insertStatements.put(table.name, insert);
            return insert;
        }

        @Override
        public void close() throws SQLException {
            SQLException closeFailure = null;
            for (PreparedStatement insert : insertStatements.values()) {
                try {
                    insert.close();
                } catch (SQLException exception) {
                    if (closeFailure == null) {
                        closeFailure = exception;
                    } else {
                        closeFailure.addSuppressed(exception);
                    }
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private record ImportResult(long recordCount, long relatedRowCount) { }

    private record ProgressUpdate(int completedKeys, int totalKeys) { }

    private static final class ProgressTracker {
        private final int totalKeys;
        private final Consumer<ProgressUpdate> updates;
        private final Set<String> completedKeys = new LinkedHashSet<>();

        private ProgressTracker(Set<String> uniqueKeys, Consumer<ProgressUpdate> updates) {
            this.totalKeys = uniqueKeys.size();
            this.updates = updates;
        }

        private void markKey(String key) {
            if (completedKeys.add(key)) {
                publish();
            }
        }

        private void publish() {
            updates.accept(new ProgressUpdate(completedKeys.size(), totalKeys));
        }
    }

    private static final class ProgressWindow {
        private final JDialog dialog;
        private final JProgressBar progressBar = new JProgressBar(0, 100);
        private final JLabel keyCountLabel = new JLabel("Scanning JSON keys...");

        private ProgressWindow(JFrame owner, String fileName) {
            dialog = new JDialog(owner, "Import progress", Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            JPanel content = new JPanel(new BorderLayout(10, 12));
            content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            content.add(new JLabel("Importing " + fileName), BorderLayout.NORTH);

            progressBar.setIndeterminate(true);
            progressBar.setStringPainted(true);
            content.add(progressBar, BorderLayout.CENTER);
            content.add(keyCountLabel, BorderLayout.SOUTH);

            dialog.setContentPane(content);
            dialog.setSize(440, 145);
            dialog.setLocationRelativeTo(owner);
        }

        private void setVisible(boolean visible) {
            dialog.setVisible(visible);
        }

        private void updateProgress(ProgressUpdate update) {
            progressBar.setIndeterminate(false);
            int percent = update.totalKeys() == 0
                    ? 100
                    : (int) (100L * update.completedKeys() / update.totalKeys());
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
            keyCountLabel.setText(String.format("%,d / %,d unique keys",
                    update.completedKeys(), update.totalKeys()));
        }

        private void scheduleUpdate(ProgressUpdate update) {
            SwingUtilities.invokeLater(() -> updateProgress(update));
        }

        private void dispose() {
            dialog.dispose();
        }
    }

    private static Path ensureSqliteExtension(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.contains(".")) {
            return path;
        }
        return path.resolveSibling(fileName + ".sqlite");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void setBusy(boolean busy) {
        inputBrowseButton.setEnabled(!busy);
        outputBrowseButton.setEnabled(!busy);
        convertButton.setEnabled(!busy);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "Conversion error", JOptionPane.ERROR_MESSAGE);
    }
}