package project;

import com.seedfinding.mccore.version.MCVersion;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

public class LowYSwampHutForFixedSeedEn extends JFrame {
    // Default values - single seed search
    private static final int DEFAULT_MIN_X = -58594;
    private static final int DEFAULT_MAX_X = 58593;
    private static final int DEFAULT_MIN_Z = -58594;
    private static final int DEFAULT_MAX_Z = 58593;
    // Default values - search from seed list
    private static final int DEFAULT_LIST_MIN_X = -128;
    private static final int DEFAULT_LIST_MAX_X = 128;
    private static final int DEFAULT_LIST_MIN_Z = -128;
    private static final int DEFAULT_LIST_MAX_Z = 128;
    private static final int DEFAULT_THREAD_COUNT = 8;
    
    // Single seed search related components
    private JTextField searchSeedField;
    private JTextField searchThreadCountField;
    private JComboBox<String> maxHeightComboBox;
    private JComboBox<String> versionComboBox;
    private JTextField minXField;
    private JTextField maxXField;
    private JTextField minZField;
    private JTextField maxZField;
    private JCheckBox searchCheckGenerationCheckBox;
    private JButton searchStartButton;
    private JButton searchPauseButton;
    private JButton searchStopButton;
    private JButton searchResetButton;
    private JButton searchExportButton;
    private JButton searchSortButton;
    private JProgressBar searchProgressBar;
    private JLabel searchElapsedTimeLabel;
    private JLabel searchRemainingTimeLabel;
    private JTextArea searchResultArea;
    private SearchCoords searcher;
    private volatile boolean isSearchRunning = false;
    private volatile boolean isSearchPaused = false;
    private long lastSearchSeed = 0;
    private int lastSearchMinX = 0;
    private int lastSearchMaxX = 0;
    private int lastSearchMinZ = 0;
    private int lastSearchMaxZ = 0;
    private double lastSearchMaxHeight = 0;
    private int lastSearchThreadCount = 0;
    
    // Search from seed list related components
    private JButton listSearchSeedFileButton;
    private JLabel listSearchSeedFileLabel;
    private File selectedSeedFile;
    private JTextField listSearchThreadCountField;
    private JComboBox<String> listMaxHeightComboBox;
    private JComboBox<String> listVersionComboBox;
    private JTextField listMinXField;
    private JTextField listMaxXField;
    private JTextField listMinZField;
    private JTextField listMaxZField;
    private JCheckBox listSearchCheckGenerationCheckBox;
    private JButton listSearchStartButton;
    private JButton listSearchPauseButton;
    private JButton listSearchStopButton;
    private JButton listSearchResetButton;
    private JButton listSearchExportButton;
    private JButton listSearchExportSeedListButton;
    private JButton listSortByYButton;
    private JButton listSortByDistanceButton;
    private JProgressBar listSearchProgressBar;
    private JLabel listSearchElapsedTimeLabel;
    private JLabel listSearchRemainingTimeLabel;
    private JLabel listSearchCurrentSeedProgressLabel;
    private JTextArea listSearchResultArea;
    private SearchCoords listSearcher;
    private volatile boolean isListSearchRunning = false;
    private volatile boolean isListSearchPaused = false;
    private int lastListSearchMinX = 0;
    private int lastListSearchMaxX = 0;
    private int lastListSearchMinZ = 0;
    private int lastListSearchMaxZ = 0;
    private double lastListSearchMaxHeight = 0;
    private int lastListSearchThreadCount = 0;
    // Store results for each seed
    private Map<Long, List<String>> seedResults = new HashMap<>();
    
    // Loaded font
    private Font loadedFont = null;

    public LowYSwampHutForFixedSeedEn() {
        setTitle("Minecraft Java Edition Low Y Swamp Hut Search Tool");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Set window icon
        setWindowIcon();
        
        // Set Chinese font
        setChineseFont();

        // Create tabs
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Single Seed Search", createSingleSeedSearchPanel());
        tabbedPane.addTab("Search from Seed List", createListSearchPanel());
        add(tabbedPane, BorderLayout.CENTER);
        
        pack();
        setSize(1350, 800);
        setLocationRelativeTo(null);
    }

    // Create single seed search panel
    private JPanel createSingleSeedSearchPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Left side: input and progress
        JPanel leftPanel = new JPanel(new BorderLayout());
        
        // Input area
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Seed input
        gbc.gridx = 0;
        gbc.gridy = 0;
        JLabel seedLabel = new JLabel("Seed:");
        seedLabel.setFont(getLoadedFont());
        inputPanel.add(seedLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchSeedField = new JTextField("", 20);
        // Add input validation, prompt when not an integer
        searchSeedField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchSeedField, "Seed");
            }
        });
        inputPanel.add(searchSeedField, gbc);

        // Thread Count input
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel threadLabel = new JLabel("Thread Count:");
        threadLabel.setFont(getLoadedFont());
        inputPanel.add(threadLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchThreadCountField = new JTextField(String.valueOf(DEFAULT_THREAD_COUNT), 20);
        // Add input validation, prompt when not an integer
        searchThreadCountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchThreadCountField, "Thread Count");
            }
        });
        inputPanel.add(searchThreadCountField, gbc);

        // Height filter dropdown
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel heightLabel = new JLabel("Filter Witch Hut Height (The Higher, the slower):");
        heightLabel.setFont(getLoadedFont());
        inputPanel.add(heightLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] heightOptions = {"0", "-10", "-20", "-30", "-40"};
        maxHeightComboBox = new JComboBox<>(heightOptions);
        maxHeightComboBox.setSelectedIndex(4); // Default select -40
        inputPanel.add(maxHeightComboBox, gbc);

        // Version selection dropdown
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel versionLabel = new JLabel("Version:");
        versionLabel.setFont(getLoadedFont());
        inputPanel.add(versionLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] versionOptions = {"1.21.1", "1.20.1", "1.19.2", "1.18.2"};
        versionComboBox = new JComboBox<>(versionOptions);
        versionComboBox.setSelectedIndex(0); // Default select 1.21.1
        inputPanel.add(versionComboBox, gbc);

        // MinX input
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel minXLabel = new JLabel("MinX(x512):");
        minXLabel.setFont(getLoadedFont());
        inputPanel.add(minXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        minXField = new JTextField(String.valueOf(DEFAULT_MIN_X), 20);
        // Add input validation, prompt when not an integer
        minXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(minXField, "MinX");
            }
        });
        inputPanel.add(minXField, gbc);

        // MaxX input
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel maxXLabel = new JLabel("MaxX(x512):");
        maxXLabel.setFont(getLoadedFont());
        inputPanel.add(maxXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        maxXField = new JTextField(String.valueOf(DEFAULT_MAX_X), 20);
        maxXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(maxXField, "MaxX");
            }
        });
        inputPanel.add(maxXField, gbc);

        // MinZ input
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel minZLabel = new JLabel("MinZ(x512):");
        minZLabel.setFont(getLoadedFont());
        inputPanel.add(minZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        minZField = new JTextField(String.valueOf(DEFAULT_MIN_Z), 20);
        minZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(minZField, "MinZ");
            }
        });
        inputPanel.add(minZField, gbc);

        // MaxZ input
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel maxZLabel = new JLabel("MaxZ(x512):");
        maxZLabel.setFont(getLoadedFont());
        inputPanel.add(maxZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        maxZField = new JTextField(String.valueOf(DEFAULT_MAX_Z), 20);
        maxZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(maxZField, "MaxZ");
            }
        });
        inputPanel.add(maxZField, gbc);

        // Precise generation check checkbox
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel checkGenerationLabel = new JLabel("Precise Generation Check (Efficiency might suffer):");
        checkGenerationLabel.setFont(getLoadedFont());
        inputPanel.add(checkGenerationLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchCheckGenerationCheckBox = new JCheckBox();
        searchCheckGenerationCheckBox.setSelected(true); // Default selected
        searchCheckGenerationCheckBox.setFont(getLoadedFont());
        inputPanel.add(searchCheckGenerationCheckBox, gbc);

        // Button area
        JPanel buttonPanel = new JPanel(new FlowLayout());
        searchStartButton = new JButton("Start Search");
        searchPauseButton = new JButton("Pause");
        searchStopButton = new JButton("Stop");
        searchResetButton = new JButton("Reset X Z to World Boundary");
        searchPauseButton.setEnabled(false);
        searchStopButton.setEnabled(false);
        buttonPanel.add(searchStartButton);
        buttonPanel.add(searchPauseButton);
        buttonPanel.add(searchStopButton);
        buttonPanel.add(searchResetButton);

        // Static text display area (placed above buttons)
        JPanel creditPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        creditPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel creditLabel = new JLabel("<html><div style='text-align: center;'><br><br><br><br><br>Every seed<br>has a dream of a low Y swamp hut<br>——SunnySlopes<br>Author: M33Galaxy<br>Font: Jiangcheng Heiti</div></html>");
        creditLabel.setFont(getLoadedFont()); // Use loaded font
        creditPanel.add(creditLabel);

        // Place credit and buttons in a container, credit on top, buttons below
        JPanel creditButtonPanel = new JPanel(new BorderLayout());
        creditButtonPanel.add(creditPanel, BorderLayout.NORTH);
        creditButtonPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Progress area
        JPanel progressPanel = new JPanel(new GridBagLayout());
        GridBagConstraints pgc = new GridBagConstraints();
        pgc.insets = new Insets(5, 5, 5, 5);
        pgc.anchor = GridBagConstraints.WEST;
        pgc.fill = GridBagConstraints.HORIZONTAL;
        pgc.weightx = 1.0;

        pgc.gridx = 0;
        pgc.gridy = 0;
        pgc.gridwidth = 2;
        searchProgressBar = new JProgressBar(0, 100);
        searchProgressBar.setStringPainted(true);
        searchProgressBar.setString("Progress: 0/0 (0.00%)");
        progressPanel.add(searchProgressBar, pgc);

        pgc.gridwidth = 1;
        pgc.gridy = 1;
        searchElapsedTimeLabel = new JLabel("Elapsed Time: 0d 0h 0m 0s");
        progressPanel.add(searchElapsedTimeLabel, pgc);

        pgc.gridy = 3;
        searchRemainingTimeLabel = new JLabel("Remaining Time: Calculating...");
        progressPanel.add(searchRemainingTimeLabel, pgc);

        leftPanel.add(inputPanel, BorderLayout.NORTH);
        leftPanel.add(creditButtonPanel, BorderLayout.CENTER);
        
        // Place progress area in another container
        JPanel leftBottomPanel = new JPanel(new BorderLayout());
        leftBottomPanel.add(progressPanel, BorderLayout.CENTER);
        
        JPanel leftContainer = new JPanel(new BorderLayout());
        leftContainer.add(leftPanel, BorderLayout.CENTER);
        leftContainer.add(leftBottomPanel, BorderLayout.SOUTH);

        // Right side: result display
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("Search Results (Actual Y value within ±1 block of output, precise search indicates if hut can actually spawn)"));
        searchResultArea = new JTextArea();
        searchResultArea.setEditable(false);
        searchResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(searchResultArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel exportSortPanel = new JPanel(new FlowLayout());
        searchExportButton = new JButton("Export");
        searchExportButton.addActionListener(e -> exportSearchResults());
        searchSortButton = new JButton("Sort");
        searchSortButton.addActionListener(e -> sortSearchResults());
        exportSortPanel.add(searchExportButton);
        exportSortPanel.add(searchSortButton);
        rightPanel.add(scrollPane, BorderLayout.CENTER);
        rightPanel.add(exportSortPanel, BorderLayout.SOUTH);

        // Use JSplitPane to split
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftContainer, rightPanel);
        splitPane.setDividerLocation(600);
        splitPane.setResizeWeight(0.5);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Add event listeners
        searchStartButton.addActionListener(e -> startSearch());
        searchPauseButton.addActionListener(e -> toggleSearchPause());
        searchStopButton.addActionListener(e -> stopSearch());
        searchResetButton.addActionListener(e -> resetSearchToDefaults());

        // Add input field listeners to detect parameter changes
        addSearchParameterListeners();

        return mainPanel;
    }


    // Add search parameter listeners to detect parameter changes (excluding thread count)
    private void addSearchParameterListeners() {
        // Seed change listener (add check on existing listener)
        searchSeedField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
        
        // Height filter change listener
        maxHeightComboBox.addActionListener(e -> checkSearchParameterChange());
        
        // Coordinate change listener
        minXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
        maxXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
        minZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
        maxZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
    }
    
    // Check if search parameters have changed (except thread count)
    private void checkSearchParameterChange() {
        if (isSearchRunning && !isSearchPaused) {
            return; // Running and not paused, don't check
        }
        
        if (!isSearchPaused) {
            return; // Not paused, don't check
        }
        
        try {
            String seedText = searchSeedField.getText().trim();
            if (seedText.isEmpty()) {
                return;
            }
            long seed = Long.parseLong(seedText);
            String selectedHeight = (String) maxHeightComboBox.getSelectedItem();
            assert selectedHeight != null;
            double maxHeight = Double.parseDouble(selectedHeight);
            int minX = Integer.parseInt(minXField.getText().trim());
            int maxX = Integer.parseInt(maxXField.getText().trim());
            int minZ = Integer.parseInt(minZField.getText().trim());
            int maxZ = Integer.parseInt(maxZField.getText().trim());
            
            // If parameters changed and in paused state, reset progress (thread count change doesn't trigger reset)
            if (seed != lastSearchSeed || minX != lastSearchMinX || 
                maxX != lastSearchMaxX || minZ != lastSearchMinZ || maxZ != lastSearchMaxZ || 
                maxHeight != lastSearchMaxHeight) {
                // Stop current search
                if (searcher != null) {
                    searcher.stop();
                }
                isSearchRunning = false;
                isSearchPaused = false;
                searchStartButton.setEnabled(true);
                searchPauseButton.setEnabled(false);
                searchPauseButton.setText("Pause");
                searchStopButton.setEnabled(false);
                searchResetButton.setEnabled(true);
                searchSeedField.setEnabled(true);
                searchThreadCountField.setEnabled(true);
                maxHeightComboBox.setEnabled(true);
                versionComboBox.setEnabled(true);
                minXField.setEnabled(true);
                maxXField.setEnabled(true);
                minZField.setEnabled(true);
                maxZField.setEnabled(true);
                searchCheckGenerationCheckBox.setEnabled(true);
                searchResultArea.setText("");
                searchProgressBar.setValue(0);
                searchProgressBar.setString("Progress: 0/0 (0.00%)");
                searchRemainingTimeLabel.setText("Remaining Time: Reset (Parameters Changed)");
            }
        } catch (NumberFormatException e) {
            // Ignore invalid input
        }
    }

    
    // Validate integer input
    private void validateIntegerInput(JTextField field, String fieldName) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return; // Empty value not validated, will be validated when starting
        }
        try {
            // Try to parse as double, check if it's an integer
            double value = Double.parseDouble(text);
            if (value != Math.floor(value)) {
                JOptionPane.showMessageDialog(this, fieldName + " must be an integer", "Input Error", JOptionPane.ERROR_MESSAGE);
                field.requestFocus();
            }
        } catch (NumberFormatException e) {
            // Not a number, will be validated when starting
        }
    }
    
    
    // Sort search results (by y value from low to high, format /tp x y z, non-generatable ones at the end)
    private void sortSearchResults() {
        String text = searchResultArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        
        String[] lines = text.split("\n");
        List<String[]> validResults = new ArrayList<>(); // Generatable results
        List<String[]> invalidResults = new ArrayList<>(); // Non-generatable results
        List<String> otherLines = new ArrayList<>(); // Other invalid lines
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Format: /tp x y z or /tp x y z cannot spawn
            if (line.startsWith("/tp ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        double y = Double.parseDouble(parts[1]);
                        boolean cannotSpawn = line.contains("×");
                        if (cannotSpawn) {
                            invalidResults.add(new String[]{String.valueOf(y), line});
                        } else {
                            validResults.add(new String[]{String.valueOf(y), line});
                        }
                    } catch (NumberFormatException e) {
                        otherLines.add(line);
                    }
                } else {
                    otherLines.add(line);
                }
            } else {
                otherLines.add(line);
            }
        }
        
        // Sort: generatable by y value from low to high, non-generatable also by y value from low to high
        validResults.sort((a, b) -> {
            double y1 = Double.parseDouble(a[0]);
            double y2 = Double.parseDouble(b[0]);
            return Double.compare(y1, y2);
        });
        invalidResults.sort((a, b) -> {
            double y1 = Double.parseDouble(a[0]);
            double y2 = Double.parseDouble(b[0]);
            return Double.compare(y1, y2);
        });
        
        // Recombine text: generatable first, then non-generatable
        StringBuilder sb = new StringBuilder();
        for (String[] result : validResults) {
            sb.append(result[1]).append("\n");
        }
        for (String[] result : invalidResults) {
            sb.append(result[1]).append("\n");
        }
        for (String invalid : otherLines) {
            sb.append(invalid).append("\n");
        }
        
        searchResultArea.setText(sb.toString());
    }

    // Search related methods
    private void startSearch() {
        // If currently paused, directly resume (don't restart)
        if (isSearchRunning && isSearchPaused) {
            // Check if thread count changed
            try {
                String threadText = searchThreadCountField.getText().trim();
                int threadCount = Integer.parseInt(threadText);
                if (threadCount < 1) {
                    JOptionPane.showMessageDialog(this, "Thread count must be greater than 0", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // Check if thread count exceeds CPU cores
                int cpuThreads = Runtime.getRuntime().availableProcessors();
                if (threadCount > cpuThreads) {
                    int result = JOptionPane.showConfirmDialog(
                        this,
                        "Thread count exceeds CPU cores (" + cpuThreads + "), automatically adjust to " + cpuThreads + "?",
                        "Prompt",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    );
                    if (result == JOptionPane.YES_OPTION) {
                        threadCount = cpuThreads;
                        searchThreadCountField.setText(String.valueOf(cpuThreads));
                    } else {
                        return;
                    }
                }
                
                // If thread count changed, adjust thread count (no popup, don't clear progress)
                if (threadCount != lastSearchThreadCount) {
                    // Get other parameters
                    String selectedHeight = (String) maxHeightComboBox.getSelectedItem();
                    assert selectedHeight != null;
                    double maxHeight = Double.parseDouble(selectedHeight);
                    String selectedVersion = (String) versionComboBox.getSelectedItem();
                    MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");
                    
                    // If version changed, need to recreate searcher
                    if (searcher == null || !searcher.getMCVersion().equals(mcVersion)) {
                        searcher = new SearchCoords(mcVersion);
                    }
                    
                    // Call startSearch, it will detect paused state and adjust thread count
                    String seedText = searchSeedField.getText().trim();
                    long seed = Long.parseLong(seedText);
                    int minX = Integer.parseInt(minXField.getText().trim());
                    int maxX = Integer.parseInt(maxXField.getText().trim());
                    int minZ = Integer.parseInt(minZField.getText().trim());
                    int maxZ = Integer.parseInt(maxZField.getText().trim());
                    
                    boolean checkGeneration = searchCheckGenerationCheckBox.isSelected();
                    searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight, 
                            this::updateSearchProgress, this::addSearchResult, checkGeneration);
                    
                    lastSearchThreadCount = threadCount;
                    searchPauseButton.setText("Pause");
                    searchThreadCountField.setEnabled(false);
                    return;
                } else {
                    // Thread count unchanged, directly resume
                    searcher.resume();
                    isSearchPaused = false;
                    searchPauseButton.setText("Pause");
                    searchThreadCountField.setEnabled(false);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, 
                    "Thread count format error, cannot continue", 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        
        try {
            // Validate seed
            String seedText = searchSeedField.getText().trim();
            if (seedText.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter seed value", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Check if seed is an integer
            double seedDouble;
            try {
                seedDouble = Double.parseDouble(seedText);
                if (seedDouble != Math.floor(seedDouble)) {
                    JOptionPane.showMessageDialog(this, "Seed must be an integer", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Seed format error, please enter an integer", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Check if seed exceeds MC normal seed boundary (absolute value exceeds 2^63-1)
            long seed;
            try {
                seed = Long.parseLong(seedText);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Seed value out of range (absolute value cannot exceed 2^63-1)", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Validate thread count
            String threadText = searchThreadCountField.getText().trim();
            double threadDouble;
            try {
                threadDouble = Double.parseDouble(threadText);
                if (threadDouble != Math.floor(threadDouble)) {
                    JOptionPane.showMessageDialog(this, "Thread count must be an integer", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Thread count format error, please enter an integer", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            int threadCount = (int) threadDouble;
            if (threadCount < 1) {
                JOptionPane.showMessageDialog(this, "Thread count must be greater than 0", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Check if thread count exceeds CPU cores
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            if (threadCount > cpuThreads) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "Thread count exceeds CPU cores (" + cpuThreads + "), automatically adjust to " + cpuThreads + "?",
                    "Prompt",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (result == JOptionPane.YES_OPTION) {
                    threadCount = cpuThreads;
                    searchThreadCountField.setText(String.valueOf(cpuThreads));
                } else {
                    return;
                }
            }
            
            String selectedHeight = (String) maxHeightComboBox.getSelectedItem();
            assert selectedHeight != null;
            double maxHeight = Double.parseDouble(selectedHeight);
            
            // Validate XZ coordinates
            String minXText = minXField.getText().trim();
            String maxXText = maxXField.getText().trim();
            String minZText = minZField.getText().trim();
            String maxZText = maxZField.getText().trim();
            
            // Check if integer
            double minXDouble, maxXDouble, minZDouble, maxZDouble;
            try {
                minXDouble = Double.parseDouble(minXText);
                if (minXDouble != Math.floor(minXDouble)) {
                    JOptionPane.showMessageDialog(this, "MinX must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    minXField.setText(String.valueOf(DEFAULT_MIN_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinX format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                minXField.setText(String.valueOf(DEFAULT_MIN_X));
                return;
            }
            
            try {
                maxXDouble = Double.parseDouble(maxXText);
                if (maxXDouble != Math.floor(maxXDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxX must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxX format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                return;
            }
            
            try {
                minZDouble = Double.parseDouble(minZText);
                if (minZDouble != Math.floor(minZDouble)) {
                    JOptionPane.showMessageDialog(this, "MinZ must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinZ format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                return;
            }
            
            try {
                maxZDouble = Double.parseDouble(maxZText);
                if (maxZDouble != Math.floor(maxZDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxZ must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxZ format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                return;
            }
            
            int minX = (int) minXDouble;
            int maxX = (int) maxXDouble;
            int minZ = (int) minZDouble;
            int maxZ = (int) maxZDouble;
            
            if (minX >= maxX) {
                JOptionPane.showMessageDialog(this, "MinX must be less than MaxX", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (minZ >= maxZ) {
                JOptionPane.showMessageDialog(this, "MinZ must be less than MaxZ", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Check world boundary: minX < -58594, maxX > 58593, minZ < -58594, maxZ > 58593
            boolean outOfBounds = minX < DEFAULT_MIN_X || maxX > DEFAULT_MAX_X || minZ < DEFAULT_MIN_Z || maxZ > DEFAULT_MAX_Z;

            if (outOfBounds) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "Input values exceed world boundary, searched huts may be unreachable, continue?",
                    "Warning",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                if (result != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            // Save current parameters
            lastSearchSeed = seed;
            lastSearchMinX = minX;
            lastSearchMaxX = maxX;
            lastSearchMinZ = minZ;
            lastSearchMaxZ = maxZ;
            lastSearchMaxHeight = maxHeight;
            lastSearchThreadCount = threadCount;

            isSearchRunning = true;
            isSearchPaused = false;
            searchStartButton.setEnabled(false);
            searchPauseButton.setEnabled(true);
            searchPauseButton.setText("Pause");
            searchStopButton.setEnabled(true);
            searchResetButton.setEnabled(false);
            searchSeedField.setEnabled(false);
            searchThreadCountField.setEnabled(false); // Cannot modify while running, can modify when paused
            maxHeightComboBox.setEnabled(false);
            versionComboBox.setEnabled(false);
            minXField.setEnabled(false);
            maxXField.setEnabled(false);
            minZField.setEnabled(false);
            maxZField.setEnabled(false);
            searchCheckGenerationCheckBox.setEnabled(false);
            searchResultArea.setText("");
            searchProgressBar.setValue(0);
            searchProgressBar.setString("Progress: 0/0 (0.00%)");
            searchElapsedTimeLabel.setText("Elapsed Time: 0d 0h 0m 0s");
            searchRemainingTimeLabel.setText("Remaining Time: Calculating...");

            // Get selected version
            String selectedVersion = (String) versionComboBox.getSelectedItem();
            MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");

            searcher = new SearchCoords(mcVersion);
            boolean checkGeneration = searchCheckGenerationCheckBox.isSelected();
            searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight, this::updateSearchProgress, this::addSearchResult, checkGeneration);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter a valid number", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleSearchPause() {
        if (searcher == null || !isSearchRunning) {
            return;
        }
        
        if (isSearchPaused) {
            // Resume (thread count changes will be handled in startSearch)
            searcher.resume();
            isSearchPaused = false;
            searchPauseButton.setText("Pause");
            searchThreadCountField.setEnabled(false); // Cannot modify thread count after resuming
        } else {
            // Pause
            searcher.pause();
            isSearchPaused = true;
            searchPauseButton.setText("Resume");
            searchThreadCountField.setEnabled(true); // Can modify thread count when paused
        }
    }

    private void stopSearch() {
        if (searcher != null) {
            searcher.stop();
        }
        isSearchRunning = false;
        isSearchPaused = false;
        searchStartButton.setEnabled(true);
        searchPauseButton.setEnabled(false);
        searchPauseButton.setText("Pause");
        searchStopButton.setEnabled(false);
        searchResetButton.setEnabled(true);
        searchSeedField.setEnabled(true);
        searchThreadCountField.setEnabled(true);
        maxHeightComboBox.setEnabled(true);
        versionComboBox.setEnabled(true);
        minXField.setEnabled(true);
        maxXField.setEnabled(true);
        minZField.setEnabled(true);
        maxZField.setEnabled(true);
        searchCheckGenerationCheckBox.setEnabled(true);
        searchRemainingTimeLabel.setText("Remaining Time: Stopped");
    }

    private void resetSearchToDefaults() {
        minXField.setText(String.valueOf(DEFAULT_MIN_X));
        maxXField.setText(String.valueOf(DEFAULT_MAX_X));
        minZField.setText(String.valueOf(DEFAULT_MIN_Z));
        maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
    }
    
    // Create search from seed list panel
    private JPanel createListSearchPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Left side: input and progress
        JPanel leftPanel = new JPanel(new BorderLayout());
        
        // Input area
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Seed file selection
        gbc.gridx = 0;
        gbc.gridy = 0;
        JLabel seedLabel = new JLabel("Seed File:");
        seedLabel.setFont(getLoadedFont());
        inputPanel.add(seedLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JPanel seedFilePanel = new JPanel(new BorderLayout());
        listSearchSeedFileButton = new JButton("Select File");
        listSearchSeedFileButton.addActionListener(e -> selectSeedFile());
        listSearchSeedFileLabel = new JLabel("No file selected");
        listSearchSeedFileLabel.setFont(getLoadedFont());
        seedFilePanel.add(listSearchSeedFileButton, BorderLayout.WEST);
        seedFilePanel.add(listSearchSeedFileLabel, BorderLayout.CENTER);
        inputPanel.add(seedFilePanel, gbc);

        // Thread Count input
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel threadLabel = new JLabel("Thread Count:");
        threadLabel.setFont(getLoadedFont());
        inputPanel.add(threadLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listSearchThreadCountField = new JTextField(String.valueOf(DEFAULT_THREAD_COUNT), 20);
        listSearchThreadCountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listSearchThreadCountField, "Thread Count");
            }
        });
        inputPanel.add(listSearchThreadCountField, gbc);

        // Height filter dropdown
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel heightLabel = new JLabel("Filter Witch Hut Height (The Higher, the slower):");
        heightLabel.setFont(getLoadedFont());
        inputPanel.add(heightLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] heightOptions = {"0", "-10", "-20", "-30", "-40"};
        listMaxHeightComboBox = new JComboBox<>(heightOptions);
        listMaxHeightComboBox.setSelectedIndex(4); // Default select -40
        inputPanel.add(listMaxHeightComboBox, gbc);

        // Version selection dropdown
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel versionLabel = new JLabel("Version:");
        versionLabel.setFont(getLoadedFont());
        inputPanel.add(versionLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] versionOptions = {"1.21.1", "1.20.1", "1.19.2", "1.18.2"};
        listVersionComboBox = new JComboBox<>(versionOptions);
        listVersionComboBox.setSelectedIndex(0); // Default select 1.21.1
        inputPanel.add(listVersionComboBox, gbc);

        // MinX input
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel minXLabel = new JLabel("MinX(x512):");
        minXLabel.setFont(getLoadedFont());
        inputPanel.add(minXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMinXField = new JTextField(String.valueOf(DEFAULT_LIST_MIN_X), 20);
        listMinXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMinXField, "MinX");
            }
        });
        inputPanel.add(listMinXField, gbc);

        // MaxX input
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel maxXLabel = new JLabel("MaxX(x512):");
        maxXLabel.setFont(getLoadedFont());
        inputPanel.add(maxXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMaxXField = new JTextField(String.valueOf(DEFAULT_LIST_MAX_X), 20);
        listMaxXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMaxXField, "MaxX");
            }
        });
        inputPanel.add(listMaxXField, gbc);

        // MinZ input
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel minZLabel = new JLabel("MinZ(x512):");
        minZLabel.setFont(getLoadedFont());
        inputPanel.add(minZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMinZField = new JTextField(String.valueOf(DEFAULT_LIST_MIN_Z), 20);
        listMinZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMinZField, "MinZ");
            }
        });
        inputPanel.add(listMinZField, gbc);

        // MaxZ input
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel maxZLabel = new JLabel("MaxZ(x512):");
        maxZLabel.setFont(getLoadedFont());
        inputPanel.add(maxZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMaxZField = new JTextField(String.valueOf(DEFAULT_LIST_MAX_Z), 20);
        listMaxZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMaxZField, "MaxZ");
            }
        });
        inputPanel.add(listMaxZField, gbc);

        // Precise generation check checkbox
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel listCheckGenerationLabel = new JLabel("Precise Generation Check (Efficiency might suffer):");
        listCheckGenerationLabel.setFont(getLoadedFont());
        inputPanel.add(listCheckGenerationLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listSearchCheckGenerationCheckBox = new JCheckBox();
        listSearchCheckGenerationCheckBox.setSelected(true); // Default selected
        listSearchCheckGenerationCheckBox.setFont(getLoadedFont());
        inputPanel.add(listSearchCheckGenerationCheckBox, gbc);

        // Button area
        JPanel buttonPanel = new JPanel(new FlowLayout());
        listSearchStartButton = new JButton("Start Search");
        listSearchPauseButton = new JButton("Pause");
        listSearchStopButton = new JButton("Stop");
        listSearchResetButton = new JButton("Reset X Z to Default");
        listSearchPauseButton.setEnabled(false);
        listSearchStopButton.setEnabled(false);
        buttonPanel.add(listSearchStartButton);
        buttonPanel.add(listSearchPauseButton);
        buttonPanel.add(listSearchStopButton);
        buttonPanel.add(listSearchResetButton);

        // Static text display area (placed above buttons)
        JPanel creditPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        creditPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel creditLabel = new JLabel("<html><div style='text-align: center;'><br><br><br><br><br>Every seed<br>has a dream of a low Y swamp hut<br>——SunnySlopes<br>Author: M33Galaxy<br>Font: DejaVu Font</div></html>");
        creditLabel.setFont(getLoadedFont());
        creditPanel.add(creditLabel);

        // Place credit and buttons in a container, credit on top, buttons below
        JPanel creditButtonPanel = new JPanel(new BorderLayout());
        creditButtonPanel.add(creditPanel, BorderLayout.NORTH);
        creditButtonPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Progress area
        JPanel progressPanel = new JPanel(new GridBagLayout());
        GridBagConstraints pgc = new GridBagConstraints();
        pgc.insets = new Insets(5, 5, 5, 5);
        pgc.anchor = GridBagConstraints.WEST;
        pgc.fill = GridBagConstraints.HORIZONTAL;
        pgc.weightx = 1.0;

        pgc.gridx = 0;
        pgc.gridy = 0;
        pgc.gridwidth = 2;
        listSearchProgressBar = new JProgressBar(0, 100);
        listSearchProgressBar.setStringPainted(true);
        listSearchProgressBar.setString("Progress: 0/0 (0.00%)");
        progressPanel.add(listSearchProgressBar, pgc);

        pgc.gridwidth = 1;
        pgc.gridy = 1;
        listSearchElapsedTimeLabel = new JLabel("Elapsed Time: 0d 0h 0m 0s");
        progressPanel.add(listSearchElapsedTimeLabel, pgc);

        pgc.gridy = 2;
        listSearchCurrentSeedProgressLabel = new JLabel("Current Seed: -/-, Progress: 0.00%");
        listSearchCurrentSeedProgressLabel.setFont(getLoadedFont());
        progressPanel.add(listSearchCurrentSeedProgressLabel, pgc);

        pgc.gridy = 3;
        listSearchRemainingTimeLabel = new JLabel("Remaining Time: Calculating...");
        progressPanel.add(listSearchRemainingTimeLabel, pgc);

        leftPanel.add(inputPanel, BorderLayout.NORTH);
        leftPanel.add(creditButtonPanel, BorderLayout.CENTER);
        
        // Place progress area in another container
        JPanel leftBottomPanel = new JPanel(new BorderLayout());
        leftBottomPanel.add(progressPanel, BorderLayout.CENTER);
        
        JPanel leftContainer = new JPanel(new BorderLayout());
        leftContainer.add(leftPanel, BorderLayout.CENTER);
        leftContainer.add(leftBottomPanel, BorderLayout.SOUTH);

        // Right side: result display
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("Search Results (Actual Y value within ±1 block of output, precise search indicates if hut can actually spawn)"));
        listSearchResultArea = new JTextArea();
        listSearchResultArea.setEditable(false);
        listSearchResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(listSearchResultArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel exportPanel = new JPanel(new FlowLayout());
        listSearchExportButton = new JButton("Export");
        listSearchExportButton.addActionListener(e -> exportListSearchResults());
        listSearchExportSeedListButton = new JButton("Export Seed List");
        listSearchExportSeedListButton.addActionListener(e -> exportSeedList());
        listSortByYButton = new JButton("Sort by Y Level");
        listSortByYButton.addActionListener(e -> sortListByLowestY());
        listSortByDistanceButton = new JButton("Sort by Distance");
        listSortByDistanceButton.addActionListener(e -> sortListByDistance());
        exportPanel.add(listSearchExportButton);
        exportPanel.add(listSearchExportSeedListButton);
        exportPanel.add(listSortByYButton);
        exportPanel.add(listSortByDistanceButton);
        rightPanel.add(scrollPane, BorderLayout.CENTER);
        rightPanel.add(exportPanel, BorderLayout.SOUTH);

        // Use JSplitPane to split
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftContainer, rightPanel);
        splitPane.setDividerLocation(600);
        splitPane.setResizeWeight(0.5);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Add event listeners
        listSearchStartButton.addActionListener(e -> startListSearch());
        listSearchPauseButton.addActionListener(e -> toggleListSearchPause());
        listSearchStopButton.addActionListener(e -> stopListSearch());
        listSearchResetButton.addActionListener(e -> resetListSearchToDefaults());

        // Add input field listeners to detect parameter changes
        addListSearchParameterListeners();

        return mainPanel;
    }

    private void updateSearchProgress(SearchCoords.ProgressInfo info) {
        SwingUtilities.invokeLater(() -> {
            if (!isSearchRunning) return;

            int progress = (int) Math.min(100, info.percentage);
            searchProgressBar.setValue(progress);
            // Display progress information in progress bar
            searchProgressBar.setString(String.format("Progress: %d/%d (%.2f%%)", info.processed, info.total, info.percentage));
            
            // Don't update time when paused
            if (!isSearchPaused) {
                searchElapsedTimeLabel.setText("Elapsed Time: " + formatTime(info.elapsedMs));
                if (info.remainingMs > 0) {
                    searchRemainingTimeLabel.setText("Remaining Time: " + formatTime(info.remainingMs));
                } else {
                    searchRemainingTimeLabel.setText("Remaining Time: Calculating...");
                }
            } else {
                searchRemainingTimeLabel.setText("Remaining Time: Paused");
            }

            if (info.processed >= info.total) {
                isSearchRunning = false;
                isSearchPaused = false;
                searchStartButton.setEnabled(true);
                searchPauseButton.setEnabled(false);
                searchPauseButton.setText("Pause");
                searchStopButton.setEnabled(false);
                searchResetButton.setEnabled(true);
                searchSeedField.setEnabled(true);
                searchThreadCountField.setEnabled(true);
                maxHeightComboBox.setEnabled(true);
                versionComboBox.setEnabled(true);
                minXField.setEnabled(true);
                maxXField.setEnabled(true);
                minZField.setEnabled(true);
                maxZField.setEnabled(true);
                searchCheckGenerationCheckBox.setEnabled(true);
                // No popup, only show completion in progress bar
                searchProgressBar.setString(String.format("Progress: %d/%d (100.00%%) - Complete", info.processed, info.total));
                searchRemainingTimeLabel.setText("Remaining Time: Completed");
            }
        });
    }

    private void addSearchResult(String result) {
        SwingUtilities.invokeLater(() -> {
            searchResultArea.append(result + "\n");
            searchResultArea.setCaretPosition(searchResultArea.getDocument().getLength());
        });
    }

    private void exportSearchResults() {
        if (searchResultArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results to export", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Search Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fileChooser.setSelectedFile(new File("search_output.txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.print(searchResultArea.getText());
                JOptionPane.showMessageDialog(this, "Export successful!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }


    private String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
    }

    /**
     * Set window icon
     * Icon file should be placed in src/main/resources/icon.png or icon.ico
     */
    private void setWindowIcon() {
        try {
            // Try to load icon from resource file
            java.net.URL iconURL = getClass().getResource("/icon.png");
            if (iconURL == null) {
                iconURL = getClass().getResource("/icon.ico");
            }
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            } else {
                // If icon file not found, can create a simple default icon
                // Or use system default icon (don't set)
                System.out.println("Info: Icon file not found (icon.png or icon.ico), using system default icon");
            }
        } catch (Exception e) {
            System.err.println("Error setting icon: " + e.getMessage());
        }
    }
    
    /**
     * Set font
     * Load font.ttf font from resource file
     */
    private void setChineseFont() {
        try {
            // Load font.ttf from resource file
            java.io.InputStream fontStream = getClass().getResourceAsStream("/font.ttf");
            if (fontStream == null) {
                System.err.println("Error: Font file font.ttf not found, please ensure file is located at src/main/resources/font.ttf");
                return;
            }
            
            // Create font
            Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            fontStream.close();
            
            // Register font to system
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(font);
            
            // Create font of specified size and save
            loadedFont = font.deriveFont(Font.PLAIN, 12f);
            
            // Set global font
            UIManager.put("Label.font", loadedFont);
            UIManager.put("Button.font", loadedFont);
            UIManager.put("TextField.font", loadedFont);
            UIManager.put("TextArea.font", loadedFont);
            UIManager.put("ComboBox.font", loadedFont);
            UIManager.put("TabbedPane.font", loadedFont);
            UIManager.put("ProgressBar.font", loadedFont);
            UIManager.put("ToolTip.font", loadedFont);
            UIManager.put("Menu.font", loadedFont);
            UIManager.put("MenuItem.font", loadedFont);
            UIManager.put("CheckBox.font", loadedFont);
            UIManager.put("RadioButton.font", loadedFont);
            UIManager.put("List.font", loadedFont);
            UIManager.put("Table.font", loadedFont);
            UIManager.put("Tree.font", loadedFont);
            
            System.out.println("Successfully loaded font: " + font.getFontName() + " (Size: " + loadedFont.getSize() + ")");
        } catch (FontFormatException e) {
            System.err.println("Font file format error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error reading font file: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error loading font: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get loaded font, return default font if not loaded
     */
    private Font getLoadedFont() {
        if (loadedFont != null) {
            return loadedFont;
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }
    
    /**
     * Convert version string to MCVersion
     */
    private MCVersion getMCVersion(String versionString) {
        switch (versionString) {
            case "1.18.2":
                return MCVersion.v1_18_2;
            case "1.19.2":
                return MCVersion.v1_19_2;
            case "1.20.1":
                return MCVersion.v1_20_1;
            case "1.21.1":
            default:
                return MCVersion.v1_21;
        }
    }
    
    // ========== Search from seed list related methods ==========
    
    // Add search parameter listeners to detect parameter changes (excluding thread count)
    private void addListSearchParameterListeners() {
        // Height filter change listener
        listMaxHeightComboBox.addActionListener(e -> checkListSearchParameterChange());
        
        // Coordinate change listener
        listMinXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
        });
        listMaxXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
        });
        listMinZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
        });
        listMaxZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkListSearchParameterChange(); }
        });
    }
    
    // Check if search parameters have changed (except thread count)
    private void checkListSearchParameterChange() {
        if (isListSearchRunning && !isListSearchPaused) {
            return; // Running and not paused, don't check
        }
        
        if (!isListSearchPaused) {
            return; // Not paused, don't check
        }
        
        try {
            if (selectedSeedFile == null) {
                return;
            }
            String selectedHeight = (String) listMaxHeightComboBox.getSelectedItem();
            assert selectedHeight != null;
            double maxHeight = Double.parseDouble(selectedHeight);
            int minX = Integer.parseInt(listMinXField.getText().trim());
            int maxX = Integer.parseInt(listMaxXField.getText().trim());
            int minZ = Integer.parseInt(listMinZField.getText().trim());
            int maxZ = Integer.parseInt(listMaxZField.getText().trim());
            
            // If parameters changed and in paused state, reset progress (thread count change doesn't trigger reset)
            if (minX != lastListSearchMinX || 
                maxX != lastListSearchMaxX || minZ != lastListSearchMinZ || maxZ != lastListSearchMaxZ || 
                maxHeight != lastListSearchMaxHeight) {
                // Stop current search
                if (listSearcher != null) {
                    listSearcher.stop();
                }
                isListSearchRunning = false;
                isListSearchPaused = false;
                listSearchStartButton.setEnabled(true);
                listSearchPauseButton.setEnabled(false);
                listSearchPauseButton.setText("Pause");
                listSearchStopButton.setEnabled(false);
                listSearchResetButton.setEnabled(true);
                listSearchSeedFileButton.setEnabled(true);
                listSearchThreadCountField.setEnabled(true);
                listMaxHeightComboBox.setEnabled(true);
                listVersionComboBox.setEnabled(true);
                listMinXField.setEnabled(true);
                listMaxXField.setEnabled(true);
                listMinZField.setEnabled(true);
                listMaxZField.setEnabled(true);
                listSearchCheckGenerationCheckBox.setEnabled(true);
                listSearchResultArea.setText("");
                listSearchProgressBar.setValue(0);
                listSearchProgressBar.setString("Progress: 0/0 (0.00%)");
                listSearchCurrentSeedProgressLabel.setText("Current Seed: -/-, Progress: 0.00%");
                listSearchRemainingTimeLabel.setText("Remaining Time: Reset (Parameters Changed)");
            }
        } catch (NumberFormatException e) {
            // Ignore invalid input
        }
    }
    
    // Select seed file
    private void selectSeedFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Seed File");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            selectedSeedFile = fileChooser.getSelectedFile();
            listSearchSeedFileLabel.setText(selectedSeedFile.getName());
        }
    }
    
    // Read seed list
    private List<Long> readSeedList(File file) throws IOException {
        List<Long> seeds = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    long seed = Long.parseLong(line);
                    seeds.add(seed);
                } catch (NumberFormatException e) {
                    // Skip invalid seed lines
                    System.err.println("Skipping invalid seed line: " + line);
                }
            }
        }
        return seeds;
    }
    
    // Search related methods
    private void startListSearch() {
        // If currently paused, directly resume (don't restart)
        if (isListSearchRunning && isListSearchPaused) {
            // Check if thread count changed
            try {
                String threadText = listSearchThreadCountField.getText().trim();
                int threadCount = Integer.parseInt(threadText);
                if (threadCount < 1) {
                    JOptionPane.showMessageDialog(this, "Thread count must be greater than 0", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // Check if thread count exceeds CPU cores
                int cpuThreads = Runtime.getRuntime().availableProcessors();
                if (threadCount > cpuThreads) {
                    int result = JOptionPane.showConfirmDialog(
                        this,
                        "Thread count exceeds CPU cores (" + cpuThreads + "), automatically adjust to " + cpuThreads + "?",
                        "Prompt",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                    );
                    if (result == JOptionPane.YES_OPTION) {
                        threadCount = cpuThreads;
                        listSearchThreadCountField.setText(String.valueOf(cpuThreads));
                    } else {
                        return;
                    }
                }
                
                // If thread count changed, adjust thread count (no popup, don't clear progress)
                if (threadCount != lastListSearchThreadCount) {
                    // Get version parameter
                    String selectedVersion = (String) listVersionComboBox.getSelectedItem();
                    MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");
                    
                    // If version changed, need to recreate searcher
                    if (listSearcher == null || !listSearcher.getMCVersion().equals(mcVersion)) {
                        listSearcher = new SearchCoords(mcVersion);
                    }
                    
                    // In batch processing mode, pause/resume functionality simplified
                    // Directly resume current seed search
                    if (listSearcher != null) {
                        listSearcher.resume();
                    }
                    isListSearchPaused = false;
                    listSearchPauseButton.setText("Pause");
                    listSearchThreadCountField.setEnabled(false);
                    return;
                } else {
                    // Thread count unchanged, directly resume
                    if (listSearcher != null) {
                        listSearcher.resume();
                    }
                    isListSearchPaused = false;
                    listSearchPauseButton.setText("Pause");
                    listSearchThreadCountField.setEnabled(false);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, 
                    "Thread count format error, cannot continue", 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        
        try {
            // Validate seed file
            if (selectedSeedFile == null || !selectedSeedFile.exists()) {
                JOptionPane.showMessageDialog(this, "Please select seed file", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Read seed list
            List<Long> seeds;
            try {
                seeds = readSeedList(selectedSeedFile);
                if (seeds.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Seed file is empty or has no valid seeds", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                // Set progress bar to 0/seed count when importing
                final long totalSeeds = seeds.size();
                SwingUtilities.invokeLater(() -> {
                    listSearchProgressBar.setMaximum((int) totalSeeds);
                    listSearchProgressBar.setValue(0);
                    listSearchProgressBar.setString(String.format("Progress: 0/%d (0.00%%)", totalSeeds));
                    listSearchCurrentSeedProgressLabel.setText("Current Seed: -/-, Progress: 0.00%");
                });
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to read seed file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Validate thread count
            String threadText = listSearchThreadCountField.getText().trim();
            double threadDouble;
            try {
                threadDouble = Double.parseDouble(threadText);
                if (threadDouble != Math.floor(threadDouble)) {
                    JOptionPane.showMessageDialog(this, "Thread count must be an integer", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "Thread count format error, please enter an integer", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            int threadCount = (int) threadDouble;
            if (threadCount < 1) {
                JOptionPane.showMessageDialog(this, "Thread count must be greater than 0", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Check if thread count exceeds CPU cores
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            if (threadCount > cpuThreads) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "Thread count exceeds CPU cores (" + cpuThreads + "), automatically adjust to " + cpuThreads + "?",
                    "Prompt",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );
                if (result == JOptionPane.YES_OPTION) {
                    threadCount = cpuThreads;
                    listSearchThreadCountField.setText(String.valueOf(cpuThreads));
                } else {
                    return;
                }
            }
            
            String selectedHeight = (String) listMaxHeightComboBox.getSelectedItem();
            assert selectedHeight != null;
            double maxHeight = Double.parseDouble(selectedHeight);
            
            // Validate XZ coordinates
            String minXText = listMinXField.getText().trim();
            String maxXText = listMaxXField.getText().trim();
            String minZText = listMinZField.getText().trim();
            String maxZText = listMaxZField.getText().trim();
            
            // Check if integer
            double minXDouble, maxXDouble, minZDouble, maxZDouble;
            try {
                minXDouble = Double.parseDouble(minXText);
                if (minXDouble != Math.floor(minXDouble)) {
                    JOptionPane.showMessageDialog(this, "MinX must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinX format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
                return;
            }
            
            try {
                maxXDouble = Double.parseDouble(maxXText);
                if (maxXDouble != Math.floor(maxXDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxX must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxX format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
                return;
            }
            
            try {
                minZDouble = Double.parseDouble(minZText);
                if (minZDouble != Math.floor(minZDouble)) {
                    JOptionPane.showMessageDialog(this, "MinZ must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinZ format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
                return;
            }
            
            try {
                maxZDouble = Double.parseDouble(maxZText);
                if (maxZDouble != Math.floor(maxZDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxZ must be an integer, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                    listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxZ format error, reset to default value", "Error", JOptionPane.ERROR_MESSAGE);
                listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
                return;
            }
            
            int minX = (int) minXDouble;
            int maxX = (int) maxXDouble;
            int minZ = (int) minZDouble;
            int maxZ = (int) maxZDouble;
            
            if (minX >= maxX) {
                JOptionPane.showMessageDialog(this, "MinX must be less than MaxX", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (minZ >= maxZ) {
                JOptionPane.showMessageDialog(this, "MinZ must be less than MaxZ", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Save current parameters
            lastListSearchMinX = minX;
            lastListSearchMaxX = maxX;
            lastListSearchMinZ = minZ;
            lastListSearchMaxZ = maxZ;
            lastListSearchMaxHeight = maxHeight;
            lastListSearchThreadCount = threadCount;

            isListSearchRunning = true;
            isListSearchPaused = false;
            listSearchStartButton.setEnabled(false);
            listSearchPauseButton.setEnabled(true);
            listSearchPauseButton.setText("Pause");
            listSearchStopButton.setEnabled(true);
            listSearchResetButton.setEnabled(false);
            listSearchSeedFileButton.setEnabled(false);
            listSearchThreadCountField.setEnabled(false);
            listMaxHeightComboBox.setEnabled(false);
            listVersionComboBox.setEnabled(false);
            listMinXField.setEnabled(false);
            listMaxXField.setEnabled(false);
            listMinZField.setEnabled(false);
            listMaxZField.setEnabled(false);
            listSearchCheckGenerationCheckBox.setEnabled(false);
            listSearchResultArea.setText("");
            listSearchProgressBar.setValue(0);
            listSearchProgressBar.setString("Progress: 0/0 (0.00%)");
            listSearchCurrentSeedProgressLabel.setText("Current Seed: -/-, Progress: 0.00%");
            listSearchElapsedTimeLabel.setText("Elapsed Time: 0d 0h 0m 0s");
            listSearchRemainingTimeLabel.setText("Remaining Time: Calculating...");
            
            // Clear previous results
            seedResults.clear();

            // Get selected version
            String selectedVersion = (String) listVersionComboBox.getSelectedItem();
            MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");

            // Batch process all seeds in new thread
            final int finalThreadCount = threadCount;
            final long totalSeeds = seeds.size();
            final long startTime = System.currentTimeMillis();
            new Thread(() -> {
                final int[] processedSeedsRef = {0};
                
                for (int seedIndex = 0; seedIndex < seeds.size(); seedIndex++) {
                    if (!isListSearchRunning) {
                        break;
                    }
                    
                    final long seed = seeds.get(seedIndex);
                    final int currentSeedIndex = seedIndex + 1; // Current sequence (start from 1)

                    seedResults.put(seed, new ArrayList<>());
                    
                    listSearcher = new SearchCoords(mcVersion);
                    final long currentSeed = seed;
                    
                    // Create result callback, grouped by seed
                    Consumer<String> seedResultCallback = result -> {
                        seedResults.get(currentSeed).add(result);
                    };

                    // Create progress callback, updating current seed progress
                    Consumer<SearchCoords.ProgressInfo> seedProgressCallback = info -> {
                        SwingUtilities.invokeLater(() -> {
                            if (isListSearchRunning) {
                                listSearchCurrentSeedProgressLabel.setText(
                                    String.format("Current Seed: %d/%d, Progress: %d/%d (%.2f%%)",
                                        currentSeedIndex, totalSeeds, info.processed, info.total, info.percentage)
                                );
                            }
                        });
                    };
                    
                    // Check if current seed's corresponding area has witch huts meeting conditions
                    boolean checkGeneration = listSearchCheckGenerationCheckBox.isSelected();
                    listSearcher.startSearch(seed, finalThreadCount, minX, maxX, minZ, maxZ, maxHeight, 
                            seedProgressCallback, seedResultCallback, checkGeneration);
                    
                    // Wait for current seed search to complete
                    while (listSearcher.isRunning() && isListSearchRunning) {
                        // Wait when paused
                        while (isListSearchPaused && isListSearchRunning) {
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (!isListSearchRunning) {
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    processedSeedsRef[0]++;
                    
                    // Update progress bar: completed seeds/total seeds
                    final int completedSeeds = processedSeedsRef[0];
                    final long elapsedMs = System.currentTimeMillis() - startTime;
                    final long remainingMs = completedSeeds > 0 ? (elapsedMs * (totalSeeds - completedSeeds) / completedSeeds) : 0;
                    final double percentage = (double) completedSeeds / totalSeeds * 100.0;
                    
                    SwingUtilities.invokeLater(() -> {
                        listSearchProgressBar.setValue(completedSeeds);
                        listSearchProgressBar.setString(String.format("Progress: %d/%d (%.2f%%)", completedSeeds, totalSeeds, percentage));
                        if (!isListSearchPaused) {
                            listSearchElapsedTimeLabel.setText("Elapsed Time: " + formatTime(elapsedMs));
                            if (remainingMs > 0) {
                                listSearchRemainingTimeLabel.setText("Remaining Time: " + formatTime(remainingMs));
                            } else {
                                listSearchRemainingTimeLabel.setText("Remaining Time: Calculating...");
                            }
                        }
                    });
                    
                    // Output current seed's results (if there are witch huts meeting conditions)
                    List<String> results = seedResults.get(seed);
                    if (!results.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            listSearchResultArea.append(seed + "\n");
                            for (String result : results) {
                                listSearchResultArea.append(result + "\n");
                            }
                            listSearchResultArea.setCaretPosition(listSearchResultArea.getDocument().getLength());
                        });
                    }
                }
                
                // All seeds processed
                final long finalElapsedMs = System.currentTimeMillis() - startTime;
                SwingUtilities.invokeLater(() -> {
                    isListSearchRunning = false;
                    isListSearchPaused = false;
                    listSearchStartButton.setEnabled(true);
                    listSearchPauseButton.setEnabled(false);
                    listSearchPauseButton.setText("Pause");
                    listSearchStopButton.setEnabled(false);
                    listSearchResetButton.setEnabled(true);
                    listSearchSeedFileButton.setEnabled(true);
                    listSearchThreadCountField.setEnabled(true);
                    listMaxHeightComboBox.setEnabled(true);
                    listVersionComboBox.setEnabled(true);
                    listMinXField.setEnabled(true);
                    listMaxXField.setEnabled(true);
                    listMinZField.setEnabled(true);
                    listMaxZField.setEnabled(true);
                    listSearchCheckGenerationCheckBox.setEnabled(true);
                    listSearchProgressBar.setValue((int) totalSeeds);
                    listSearchProgressBar.setString(String.format("Progress: %d/%d (100.00%%) - Complete", totalSeeds, totalSeeds));
                    listSearchCurrentSeedProgressLabel.setText("Current Seed: -/-, Progress: 100.00%");
                    listSearchElapsedTimeLabel.setText("Elapsed Time: " + formatTime(finalElapsedMs));
                    listSearchRemainingTimeLabel.setText("Remaining Time: Completed");
                });
            }).start();

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Please enter a valid number", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleListSearchPause() {
        if (listSearcher == null || !isListSearchRunning) {
            return;
        }
        
        if (isListSearchPaused) {
            // Resume (thread count changes will be handled in startListSearch)
            listSearcher.resume();
            isListSearchPaused = false;
            listSearchPauseButton.setText("Pause");
            listSearchThreadCountField.setEnabled(false); // Cannot modify thread count after resuming
        } else {
            // Pause
            listSearcher.pause();
            isListSearchPaused = true;
            listSearchPauseButton.setText("Resume");
            listSearchThreadCountField.setEnabled(true); // Can modify thread count when paused
        }
    }

    private void stopListSearch() {
        if (listSearcher != null) {
            listSearcher.stop();
        }
        isListSearchRunning = false;
        isListSearchPaused = false;
        listSearchStartButton.setEnabled(true);
        listSearchPauseButton.setEnabled(false);
        listSearchPauseButton.setText("Pause");
        listSearchStopButton.setEnabled(false);
        listSearchResetButton.setEnabled(true);
        listSearchSeedFileButton.setEnabled(true);
        listSearchThreadCountField.setEnabled(true);
        listMaxHeightComboBox.setEnabled(true);
        listVersionComboBox.setEnabled(true);
        listMinXField.setEnabled(true);
        listMaxXField.setEnabled(true);
        listMinZField.setEnabled(true);
        listMaxZField.setEnabled(true);
        listSearchCheckGenerationCheckBox.setEnabled(true);
        listSearchCurrentSeedProgressLabel.setText("Current Seed: -/-, Progress: 0.00%");
        listSearchRemainingTimeLabel.setText("Remaining Time: Stopped");
    }

    private void resetListSearchToDefaults() {
        listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
        listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
        listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
        listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
    }
    
    // Parse result text, return mapping of seeds and coordinates
    private Map<Long, List<Coordinate>> parseListResults() {
        Map<Long, List<Coordinate>> parsedResults = new HashMap<>();
        String text = listSearchResultArea.getText().trim();
        if (text.isEmpty()) {
            return parsedResults;
        }
        
        String[] lines = text.split("\n");
        Long currentSeed = null;
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            
            // Try to parse as seed (long integer)
            try {
                long seed = Long.parseLong(line);
                currentSeed = seed;
                if (!parsedResults.containsKey(seed)) {
                    parsedResults.put(seed, new ArrayList<>());
                }
            } catch (NumberFormatException e) {
                // Not a seed, might be coordinates
                if (line.startsWith("/tp ") && currentSeed != null) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            int x = (int) Double.parseDouble(parts[0]);
                            double y = Double.parseDouble(parts[1]);
                            int z = (int) Double.parseDouble(parts[2]);
                            parsedResults.get(currentSeed).add(new Coordinate(x, y, z, line));
                        } catch (NumberFormatException ex) {
                            // Skip invalid coordinate lines
                        }
                    }
                }
            }
        }
        
        return parsedResults;
    }
    
    // Coordinate class
    private static class Coordinate {
        final int x;
        final double y;
        final int z;
        final String originalLine;
        final boolean canSpawn; // Whether it can spawn
        
        Coordinate(int x, double y, int z, String originalLine) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.originalLine = originalLine;
            this.canSpawn = !originalLine.contains("×");
        }
        
        // Calculate squared distance to origin (x² + z²), used for sorting
        double distanceSquared() {
            return (double) x * x + (double) z * z;
        }
    }
    
    // Sort by lowest y (if all huts cannot spawn, put at end, otherwise sort by lowest generatable y)
    private void sortListByLowestY() {
        Map<Long, List<Coordinate>> parsedResults = parseListResults();
        if (parsedResults.isEmpty()) {
            return;
        }
        
        // Create list of seeds and lowest y values, distinguish generatable and non-generatable
        List<Map.Entry<Long, Double>> validSeedYList = new ArrayList<>(); // Seeds with generatable huts
        List<Map.Entry<Long, Double>> invalidSeedYList = new ArrayList<>(); // Seeds where all huts cannot spawn
        
        for (Map.Entry<Long, List<Coordinate>> entry : parsedResults.entrySet()) {
            List<Coordinate> coords = entry.getValue();
            // Check if there are generatable huts
            boolean hasValid = coords.stream().anyMatch(c -> c.canSpawn);
            
            if (hasValid) {
                // If there are generatable huts, take the lowest y among generatable huts
                double minY = coords.stream()
                        .filter(c -> c.canSpawn)
                        .mapToDouble(c -> c.y)
                        .min()
                        .orElse(Double.MAX_VALUE);
                validSeedYList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minY));
            } else {
                // If all huts cannot spawn, take the lowest y among all huts (for sorting among non-generatable seeds)
                double minY = coords.stream()
                        .mapToDouble(c -> c.y)
                        .min()
                        .orElse(Double.MAX_VALUE);
                invalidSeedYList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minY));
            }
        }
        
        // Sort by lowest y value (from low to high)
        validSeedYList.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        invalidSeedYList.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        
        // Rebuild result text: generatable seeds first, then non-generatable seeds
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Double> entry : validSeedYList) {
            Long seed = entry.getKey();
            List<Coordinate> coords = parsedResults.get(seed);
            sb.append(seed).append("\n");
            for (Coordinate coord : coords) {
                sb.append(coord.originalLine).append("\n");
            }
        }
        for (Map.Entry<Long, Double> entry : invalidSeedYList) {
            Long seed = entry.getKey();
            List<Coordinate> coords = parsedResults.get(seed);
            sb.append(seed).append("\n");
            for (Coordinate coord : coords) {
                sb.append(coord.originalLine).append("\n");
            }
        }
        
        listSearchResultArea.setText(sb.toString());
    }
    
    // Sort by distance from origin (near to far) (if all huts cannot spawn, put at end, otherwise sort by nearest generatable distance)
    private void sortListByDistance() {
        Map<Long, List<Coordinate>> parsedResults = parseListResults();
        if (parsedResults.isEmpty()) {
            return;
        }
        
        // Create list of seeds and nearest distances, distinguish generatable and non-generatable
        List<Map.Entry<Long, Double>> validSeedDistanceList = new ArrayList<>(); // Seeds with generatable huts
        List<Map.Entry<Long, Double>> invalidSeedDistanceList = new ArrayList<>(); // Seeds where all huts cannot spawn
        
        for (Map.Entry<Long, List<Coordinate>> entry : parsedResults.entrySet()) {
            List<Coordinate> coords = entry.getValue();
            // Check if there are generatable huts
            boolean hasValid = coords.stream().anyMatch(c -> c.canSpawn);
            
            if (hasValid) {
                // If there are generatable huts, take the nearest distance among generatable huts
                double minDistanceSquared = coords.stream()
                        .filter(c -> c.canSpawn)
                        .mapToDouble(Coordinate::distanceSquared)
                        .min()
                        .orElse(0.0);
                validSeedDistanceList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minDistanceSquared));
            } else {
                // If all huts cannot spawn, take the nearest distance among all huts (for sorting among non-generatable seeds)
                double minDistanceSquared = coords.stream()
                        .mapToDouble(Coordinate::distanceSquared)
                        .min()
                        .orElse(0.0);
                invalidSeedDistanceList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minDistanceSquared));
            }
        }
        
        // Sort by distance (from near to far, i.e., from small to large)
        validSeedDistanceList.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        invalidSeedDistanceList.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        
        // Rebuild result text: generatable seeds first, then non-generatable seeds
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Double> entry : validSeedDistanceList) {
            Long seed = entry.getKey();
            List<Coordinate> coords = parsedResults.get(seed);
            sb.append(seed).append("\n");
            for (Coordinate coord : coords) {
                sb.append(coord.originalLine).append("\n");
            }
        }
        for (Map.Entry<Long, Double> entry : invalidSeedDistanceList) {
            Long seed = entry.getKey();
            List<Coordinate> coords = parsedResults.get(seed);
            sb.append(seed).append("\n");
            for (Coordinate coord : coords) {
                sb.append(coord.originalLine).append("\n");
            }
        }
        
        listSearchResultArea.setText(sb.toString());
    }
    
    private void exportListSearchResults() {
        if (listSearchResultArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results to export", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Search Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fileChooser.setSelectedFile(new File("search_output.txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.print(listSearchResultArea.getText());
                JOptionPane.showMessageDialog(this, "Export successful!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    // Export seed list (excluding /tp coordinates)
    private void exportSeedList() {
        if (listSearchResultArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results to export", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Parse results, extract all seeds
        String text = listSearchResultArea.getText().trim();
        String[] lines = text.split("\n");
        List<Long> seeds = new ArrayList<>();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Skip coordinate lines starting with /tp
            if (line.startsWith("/tp ")) {
                continue;
            }
            // Try to parse as seed
            try {
                long seed = Long.parseLong(line);
                if (!seeds.contains(seed)) {
                    seeds.add(seed);
                }
            } catch (NumberFormatException e) {
                // Ignore invalid lines
            }
        }
        
        if (seeds.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No seeds found", "Information", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Seed List");
        fileChooser.setFileFilter(new FileNameExtensionFilter("Text Files (*.txt)", "txt"));
        fileChooser.setSelectedFile(new File("seed_list.txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                for (Long seed : seeds) {
                    writer.println(seed);
                }
                JOptionPane.showMessageDialog(this, "Export successful! Exported " + seeds.size() + " seeds", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void main(String[] args) {
        // Note: System properties should be set in Launcher
        // Only keep necessary initialization logic here
        
        // Pre-initialize SeedCheckerSettings in main thread to avoid initialization in multi-threaded environment
        // Use try-catch to catch possible initialization errors but continue execution
        try {
            SeedCheckerInitializer.initialize();
        } catch (ExceptionInInitializerError e) {
            // If initialization fails, print warning but continue execution
            System.err.println("Warning: SeedChecker initialization failed, but continuing...");
            System.err.println("You may need to run the JAR with: java -Dlog4j2.callerClass=project.Launcher -Dlog4j2.enable.threadlocals=false -jar ...");
        }
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new LowYSwampHutForFixedSeed().setVisible(true);
        });
    }
}

