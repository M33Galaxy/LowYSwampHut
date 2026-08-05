package project;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.text.MessageFormat;

public class LowYSwampHutForFixedSeed extends JFrame {
    // ResourceBundle for internationalization
    private ResourceBundle messages;
    // 默认值 - 单种子搜索
    private static final int DEFAULT_MIN_X = -58594;
    private static final int DEFAULT_MAX_X = 58593;
    private static final int DEFAULT_MIN_Z = -58594;
    private static final int DEFAULT_MAX_Z = 58593;
    // 默认值 - 从种子列表搜索
    private static final int DEFAULT_LIST_MIN_X = -128;
    private static final int DEFAULT_LIST_MAX_X = 128;
    private static final int DEFAULT_LIST_MIN_Z = -128;
    private static final int DEFAULT_LIST_MAX_Z = 128;
    /** 批量搜索：区域格数低于此值时按种子并行（每种子 1 线程），避免每种子反复建/毁大线程池 */
    private static final long LIST_SEED_PARALLEL_MAX_AREA = 150_000L;
    /** 大范围单种子内：每线程目标区域格数 */
    private static final long TARGET_CELLS_PER_THREAD = 4096L;
    /** 结果区 UI 批量刷新间隔，减轻 EDT 卡顿 */
    private static final long LIST_RESULT_UI_FLUSH_MS = 250L;

    // 单种子搜索相关组件
    private JLabel searchSeedLabel;
    private JLabel searchThreadCountLabel;
    private JLabel searchHeightLabel;
    private JLabel searchVersionLabel;
    private JLabel searchWorldPresetLabel;
    private JLabel searchMinXLabel;
    private JLabel searchMaxXLabel;
    private JLabel searchMinZLabel;
    private JLabel searchMaxZLabel;
    private JLabel searchCheckGenerationLabel;
    private JLabel searchLanguageLabel;
    private JTextField searchSeedField;
    private JTextField searchThreadCountField;
    private JComboBox<String> maxHeightComboBox;
    private JComboBox<String> versionComboBox;
    private JComboBox<String> worldPresetComboBox;
    private JTextField minXField;
    private JTextField maxXField;
    private JTextField minZField;
    private JTextField maxZField;
    private JCheckBox squareSideCheckBox;
    private JTextField squareSideField;
    private boolean squareSideUpdating = false;
    private JCheckBox searchCheckGenerationCheckBox;
    private JComboBox<String> languageComboBox;
    private JButton searchStartButton;
    private JButton searchPauseButton;
    private JButton searchStopButton;
    private JButton searchResetButton;
    private JButton searchExportButton;
    private JButton searchSortButton;
    private JProgressBar searchProgressBar;
    private JLabel searchElapsedTimeLabel;
    private JLabel searchRemainingTimeLabel;
    private JLabel searchCreditLabel;
    private JPanel searchRightPanel;
    private JTextArea searchResultArea;
    private JLabel listSearchCreditLabel;
    private JPanel listSearchRightPanel;
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

    // 从种子列表搜索相关组件
    private JButton listSearchSeedFileButton;
    private JLabel listSearchSeedFileLabel;
    private JLabel listSearchSeedFileTitleLabel;
    private JLabel listSearchThreadCountLabel;
    private JLabel listSearchHeightLabel;
    private JLabel listSearchVersionLabel;
    private JLabel listSearchWorldPresetLabel;
    private JLabel listSearchMinXLabel;
    private JLabel listSearchMaxXLabel;
    private JLabel listSearchMinZLabel;
    private JLabel listSearchMaxZLabel;
    private JLabel listSearchCheckGenerationLabel;
    private File selectedSeedFile;
    private JTextField listSearchThreadCountField;
    private JComboBox<String> listMaxHeightComboBox;
    private JComboBox<String> listVersionComboBox;
    private JComboBox<String> listWorldPresetComboBox;
    private JTextField listMinXField;
    private JTextField listMaxXField;
    private JTextField listMinZField;
    private JTextField listMaxZField;
    private JCheckBox listSquareSideCheckBox;
    private JTextField listSquareSideField;
    private boolean listSquareSideUpdating = false;
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
    private final Set<SearchCoords> activeListSearchers = Collections.synchronizedSet(new HashSet<>());
    private ThreadPoolExecutor listSearchExecutor;
    private volatile boolean isListSearchRunning = false;
    private volatile boolean isListSearchPaused = false;
    private int lastListSearchMinX = 0;
    private int lastListSearchMaxX = 0;
    private int lastListSearchMinZ = 0;
    private int lastListSearchMaxZ = 0;
    private double lastListSearchMaxHeight = 0;
    private int lastListSearchThreadCount = 0;
    private int lastListConcurrentSeeds = 1;
    private volatile long currentListSeed = 0;
    private final AtomicInteger listProcessedSeedsRef = new AtomicInteger(0);
    // 存储每个种子的结果
    private Map<Long, List<String>> seedResults = new HashMap<>();

    // 加载的字体
    private Font loadedFont = null;
    // 当前语言Locale
    private Locale currentLocale;

    public LowYSwampHutForFixedSeed() {
        // 初始化ResourceBundle，根据系统语言选择
        Locale systemLocale = Locale.getDefault();
        // 如果系统语言是中文（zh-cn、zh-hk、zh-tw），使用中文资源，否则使用英文
        if (systemLocale.getLanguage().equals("zh")) {
            String country = systemLocale.getCountry().toLowerCase();
            if (country.equals("cn") || country.equals("hk") || country.equals("tw")) {
                currentLocale = new Locale("zh", "CN");
            } else {
                currentLocale = new Locale("en", "US");
            }
        } else {
            currentLocale = new Locale("en", "US");
        }
        try {
            messages = ResourceBundle.getBundle("messages", currentLocale);
        } catch (Exception e) {
            // 如果加载失败，使用默认的英文
            currentLocale = new Locale("en", "US");
            messages = ResourceBundle.getBundle("messages", currentLocale);
        }

        setTitle(getString("window.title"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // 设置窗口图标
        setWindowIcon();

        // 设置中文字体
        setChineseFont();

        // 创建标签页
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab(getString("tab.singleSeedSearch"), createSingleSeedSearchPanel());
        tabbedPane.addTab(getString("tab.listSearch"), createListSearchPanel());
        add(tabbedPane, BorderLayout.CENTER);

        pack();
        setSize(1350, 800);
        setLocationRelativeTo(null);
    }

    /**
     * 获取本地化字符串
     */
    private String getString(String key) {
        try {
            return messages.getString(key);
        } catch (Exception e) {
            return key; // 如果找不到，返回key本身
        }
    }

    /**
     * 获取格式化字符串
     */
    private String getString(String key, Object... args) {
        try {
            return MessageFormat.format(messages.getString(key), args);
        } catch (Exception e) {
            return key; // 如果找不到，返回key本身
        }
    }

    // 创建单种子搜索面板
    private JPanel createSingleSeedSearchPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 左侧：输入和进度
        JPanel leftPanel = new JPanel(new BorderLayout());

        // 输入区域
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Seed 输入
        gbc.gridx = 0;
        gbc.gridy = 0;
        searchSeedLabel = new JLabel(getString("label.seed"));
        searchSeedLabel.setFont(getLoadedFont());
        inputPanel.add(searchSeedLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchSeedField = new JTextField("", 20);
        // 添加输入验证，非整数时提示
        searchSeedField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchSeedField, getString("label.seed").replace(":", ""));
            }
        });
        inputPanel.add(searchSeedField, gbc);

        // Thread Count 输入
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchThreadCountLabel = new JLabel(getString("label.threadCount"));
        searchThreadCountLabel.setFont(getLoadedFont());
        inputPanel.add(searchThreadCountLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchThreadCountField = new JTextField(String.valueOf(Runtime.getRuntime().availableProcessors()), 20);
        // 添加输入验证，非整数时提示
        searchThreadCountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchThreadCountField, getString("label.threadCount").replace(":", ""));
            }
        });
        inputPanel.add(searchThreadCountField, gbc);

        // 高度筛选下拉框
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchHeightLabel = new JLabel(getString("label.heightFilter"));
        searchHeightLabel.setFont(getLoadedFont());
        inputPanel.add(searchHeightLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] heightOptions = {"0", "-10", "-20", "-30", "-40"};
        maxHeightComboBox = new JComboBox<>(heightOptions);
        maxHeightComboBox.setSelectedIndex(4); // 默认选择 -40
        inputPanel.add(maxHeightComboBox, gbc);

        // 版本选择下拉框
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchVersionLabel = new JLabel(getString("label.version"));
        searchVersionLabel.setFont(getLoadedFont());
        inputPanel.add(searchVersionLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] versionOptions = GameVersion.displayNames();
        versionComboBox = new JComboBox<>(versionOptions);
        versionComboBox.setSelectedIndex(0); // 默认选择 26.2
        inputPanel.add(versionComboBox, gbc);

        // 世界类型选择下拉框
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchWorldPresetLabel = new JLabel(getString("label.worldPreset"));
        searchWorldPresetLabel.setFont(getLoadedFont());
        inputPanel.add(searchWorldPresetLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        worldPresetComboBox = new JComboBox<>(getWorldPresetOptions());
        worldPresetComboBox.setSelectedIndex(0);
        worldPresetComboBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                updateSingleSeedPreciseGenerationCheckUi();
            }
        });
        inputPanel.add(worldPresetComboBox, gbc);

        // MinX 输入
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchMinXLabel = new JLabel(getString("label.minX"));
        searchMinXLabel.setFont(getLoadedFont());
        inputPanel.add(searchMinXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        minXField = new JTextField(String.valueOf(DEFAULT_MIN_X), 20);
        // 添加输入验证，非整数时提示
        minXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(minXField, getString("label.minX").replace(":", ""));
            }
        });
        inputPanel.add(minXField, gbc);

        // MaxX 输入
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchMaxXLabel = new JLabel(getString("label.maxX"));
        searchMaxXLabel.setFont(getLoadedFont());
        inputPanel.add(searchMaxXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        maxXField = new JTextField(String.valueOf(DEFAULT_MAX_X), 20);
        maxXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(maxXField, getString("label.maxX").replace(":", ""));
            }
        });
        inputPanel.add(maxXField, gbc);

        // MinZ 输入
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchMinZLabel = new JLabel(getString("label.minZ"));
        searchMinZLabel.setFont(getLoadedFont());
        inputPanel.add(searchMinZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        minZField = new JTextField(String.valueOf(DEFAULT_MIN_Z), 20);
        minZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(minZField, getString("label.minZ").replace(":", ""));
            }
        });
        inputPanel.add(minZField, gbc);

        // MaxZ 输入
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchMaxZLabel = new JLabel(getString("label.maxZ"));
        searchMaxZLabel.setFont(getLoadedFont());
        inputPanel.add(searchMaxZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        maxZField = new JTextField(String.valueOf(DEFAULT_MAX_Z), 20);
        maxZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(maxZField, getString("label.maxZ").replace(":", ""));
            }
        });
        inputPanel.add(maxZField, gbc);

        // 正方形区域边长（勾选后按中心 0,0 填入 min/max）
        gbc.gridx = 0;
        gbc.gridy = 9;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        squareSideCheckBox = new JCheckBox(getString("label.squareSide"));
        squareSideCheckBox.setFont(getLoadedFont());
        inputPanel.add(squareSideCheckBox, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        long defaultSide = (long) DEFAULT_MAX_X - DEFAULT_MIN_X + 1;
        squareSideField = new JTextField(String.valueOf(defaultSide), 20);
        squareSideCheckBox.setSelected(true);
        squareSideCheckBox.addActionListener(e -> onSquareSideModeToggled());
        squareSideField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applySquareSideToBounds();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applySquareSideToBounds();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applySquareSideToBounds();
            }
        });
        squareSideField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(squareSideField, getString("label.squareSide"));
            }
        });
        inputPanel.add(squareSideField, gbc);
        applyBoundFieldEnableState(true);
        applySquareSideToBounds();

        // 精确检查生成情况复选框
        gbc.gridx = 0;
        gbc.gridy = 10;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchCheckGenerationLabel = new JLabel(getString("label.checkGeneration"));
        searchCheckGenerationLabel.setFont(getLoadedFont());
        inputPanel.add(searchCheckGenerationLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchCheckGenerationCheckBox = new JCheckBox();
        searchCheckGenerationCheckBox.setSelected(true); // 默认选中
        searchCheckGenerationCheckBox.setFont(getLoadedFont());
        inputPanel.add(searchCheckGenerationCheckBox, gbc);
        updateSingleSeedPreciseGenerationCheckUi();

        // 语言选择下拉框
        gbc.gridx = 0;
        gbc.gridy = 11;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        searchLanguageLabel = new JLabel(getString("label.language"));
        searchLanguageLabel.setFont(getLoadedFont());
        inputPanel.add(searchLanguageLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] languageOptions = {"中文", "English"};
        languageComboBox = new JComboBox<>(languageOptions);
        // 根据当前语言设置默认选项
        if (currentLocale.getLanguage().equals("zh")) {
            languageComboBox.setSelectedIndex(0);
        } else {
            languageComboBox.setSelectedIndex(1);
        }
        languageComboBox.addActionListener(e -> changeLanguage());
        inputPanel.add(languageComboBox, gbc);

        // 按钮区域
        JPanel buttonPanel = new JPanel(new FlowLayout());
        searchStartButton = new JButton(getString("button.startSearch"));
        searchPauseButton = new JButton(getString("button.pause"));
        searchStopButton = new JButton(getString("button.stop"));
        searchResetButton = new JButton(getString("button.reset"));
        searchPauseButton.setEnabled(false);
        searchStopButton.setEnabled(false);
        buttonPanel.add(searchStartButton);
        buttonPanel.add(searchPauseButton);
        buttonPanel.add(searchStopButton);
        buttonPanel.add(searchResetButton);

        // 静态文字展示区域（放在按钮上方）
        JPanel creditPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        creditPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        searchCreditLabel = new JLabel(getString("credit.text"));
        searchCreditLabel.setFont(getLoadedFont()); // 使用加载的字体
        creditPanel.add(searchCreditLabel);

        // 将 credit 和按钮放在一个容器中，credit 在上，按钮在下
        JPanel creditButtonPanel = new JPanel(new BorderLayout());
        creditButtonPanel.add(creditPanel, BorderLayout.NORTH);
        creditButtonPanel.add(buttonPanel, BorderLayout.SOUTH);

        // 进度区域
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
        searchProgressBar.setString(getString("progress.format", 0, 0, 0.0));
        progressPanel.add(searchProgressBar, pgc);

        pgc.gridwidth = 1;
        pgc.gridy = 1;
        searchElapsedTimeLabel = new JLabel(getString("elapsedTime", formatTime(0)));
        progressPanel.add(searchElapsedTimeLabel, pgc);

        pgc.gridy = 3;
        searchRemainingTimeLabel = new JLabel(getString("remainingTime.calculating"));
        progressPanel.add(searchRemainingTimeLabel, pgc);

        leftPanel.add(inputPanel, BorderLayout.NORTH);
        leftPanel.add(creditButtonPanel, BorderLayout.CENTER);

        // 将进度区域放在另一个容器中
        JPanel leftBottomPanel = new JPanel(new BorderLayout());
        leftBottomPanel.add(progressPanel, BorderLayout.CENTER);

        JPanel leftContainer = new JPanel(new BorderLayout());
        leftContainer.add(leftPanel, BorderLayout.CENTER);
        leftContainer.add(leftBottomPanel, BorderLayout.SOUTH);

        // 右侧：结果显示
        searchRightPanel = new JPanel(new BorderLayout());
        searchRightPanel.setBorder(BorderFactory.createTitledBorder(getString("result.border")));
        searchResultArea = new JTextArea();
        searchResultArea.setEditable(false);
        searchResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(searchResultArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel exportSortPanel = new JPanel(new FlowLayout());
        searchExportButton = new JButton(getString("button.export"));
        searchExportButton.addActionListener(e -> exportSearchResults());
        searchSortButton = new JButton(getString("button.sort"));
        searchSortButton.addActionListener(e -> sortSearchResults());
        exportSortPanel.add(searchExportButton);
        exportSortPanel.add(searchSortButton);
        searchRightPanel.add(scrollPane, BorderLayout.CENTER);
        searchRightPanel.add(exportSortPanel, BorderLayout.SOUTH);

        // 使用 JSplitPane 分割
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftContainer, searchRightPanel);
        splitPane.setDividerLocation(600);
        splitPane.setResizeWeight(0.5);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 添加事件监听
        searchStartButton.addActionListener(e -> startSearch());
        searchPauseButton.addActionListener(e -> toggleSearchPause());
        searchStopButton.addActionListener(e -> stopSearch());
        searchResetButton.addActionListener(e -> resetSearchToDefaults());

        // 添加输入字段监听，检测参数变化
        addSearchParameterListeners();

        return mainPanel;
    }


    // 添加搜索参数监听器，检测参数变化（不包括线程数）
    private void addSearchParameterListeners() {
        // 种子变化监听（在已有监听器基础上添加检查）
        searchSeedField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }
        });

        // 高度筛选变化监听
        maxHeightComboBox.addActionListener(e -> checkSearchParameterChange());

        // 坐标变化监听
        minXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }
        });
        maxXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }
        });
        minZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }
        });
        maxZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkSearchParameterChange();
            }
        });
    }

    // 检查搜索参数是否变化（除了线程数）
    private void checkSearchParameterChange() {
        if (isSearchRunning && !isSearchPaused) {
            return; // 运行中且未暂停，不检查
        }

        if (!isSearchPaused) {
            return; // 未暂停，不检查
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

            // 如果参数变化且处于暂停状态，重置进度（线程数变化不触发重置）
            if (seed != lastSearchSeed || minX != lastSearchMinX ||
                    maxX != lastSearchMaxX || minZ != lastSearchMinZ || maxZ != lastSearchMaxZ ||
                    maxHeight != lastSearchMaxHeight) {
                // 停止当前搜索
                if (searcher != null) {
                    searcher.stop();
                }
                isSearchRunning = false;
                isSearchPaused = false;
                searchStartButton.setEnabled(true);
                searchPauseButton.setEnabled(false);
                searchPauseButton.setText(getString("button.pause"));
                searchStopButton.setEnabled(false);
                searchResetButton.setEnabled(true);
                searchSeedField.setEnabled(true);
                searchThreadCountField.setEnabled(true);
                maxHeightComboBox.setEnabled(true);
                versionComboBox.setEnabled(true);
                worldPresetComboBox.setEnabled(true);
                applyBoundFieldEnableState(true);
                updateSingleSeedPreciseGenerationCheckUi();
                if (languageComboBox != null) {
                    languageComboBox.setEnabled(true);
                }
                searchResultArea.setText("");
                searchProgressBar.setValue(0);
                searchProgressBar.setString(getString("progress.format", 0, 0, 0.0));
                searchRemainingTimeLabel.setText(getString("remainingTime.reset"));
            }
        } catch (NumberFormatException e) {
            // 忽略无效输入
        }
    }


    // 验证整数输入
    private void validateIntegerInput(JTextField field, String fieldName) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return; // 空值不验证，会在开始运行时验证
        }
        try {
            // 尝试解析为double，检查是否为整数
            double value = Double.parseDouble(text);
            if (value != Math.floor(value)) {
                JOptionPane.showMessageDialog(this, getString("validation.integerRequired", fieldName), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                field.requestFocus();
            }
        } catch (NumberFormatException e) {
            // 不是数字，会在开始运行时验证
        }
    }


    // 排序搜索结果（按y值从低到高，格式为/tp x y z，无法生成的排到最后）
    private void sortSearchResults() {
        String text = searchResultArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }

        String[] lines = text.split("\n");
        List<String[]> validResults = new ArrayList<>(); // 可生成的结果
        List<String[]> invalidResults = new ArrayList<>(); // 无法生成的结果
        List<String> otherLines = new ArrayList<>(); // 其他无效行

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 格式：/tp x y z 或 /tp x y z 无法生成
            if (line.startsWith("/tp ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        double y = Double.parseDouble(parts[1]);
                        boolean cannotGenerate = line.contains("x");
                        if (cannotGenerate) {
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

        // 排序：可生成的按y值从低到高，无法生成的也按y值从低到高
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

        // 重新组合文本：先可生成的，后无法生成的
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

    // 搜索相关方法
    private void startSearch() {
        // 如果当前处于暂停状态，直接恢复（不重新开始）
        if (isSearchRunning && isSearchPaused) {
            // 检查线程数是否变化
            try {
                String threadText = searchThreadCountField.getText().trim();
                int threadCount = Integer.parseInt(threadText);
                if (threadCount < 1) {
                    JOptionPane.showMessageDialog(this, getString("error.threadCountRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 检查线程数是否超过CPU核数
                int cpuThreads = Runtime.getRuntime().availableProcessors();
                if (threadCount > cpuThreads) {
                    int result = JOptionPane.showConfirmDialog(
                            this,
                            getString("error.threadCountExceedsCPU", cpuThreads, cpuThreads),
                            getString("prompt.adjustThreadCount"),
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

                // 如果线程数变化，调整线程数（不弹框，不清除进度）
                if (threadCount != lastSearchThreadCount) {
                    // 获取其他参数
                    String selectedHeight = (String) maxHeightComboBox.getSelectedItem();
                    assert selectedHeight != null;
                    double maxHeight = Double.parseDouble(selectedHeight);
                    String selectedVersion = (String) versionComboBox.getSelectedItem();
                    GameVersion gameVersion = GameVersion.fromDisplayName(selectedVersion);
                    WorldPresetMode worldPresetMode = getWorldPresetMode((String) worldPresetComboBox.getSelectedItem());

                    // 如果版本变化，需要重新创建searcher
                    if (searcher == null || !searcher.getGameVersion().equals(gameVersion) || searcher.getWorldPresetMode() != worldPresetMode) {
                        searcher = new SearchCoords(gameVersion, worldPresetMode);
                    }

                    // 调用startSearch，它会检测到暂停状态并调整线程数
                    String seedText = searchSeedField.getText().trim();
                    long seed = Long.parseLong(seedText);
                    int minX = Integer.parseInt(minXField.getText().trim());
                    int maxX = Integer.parseInt(maxXField.getText().trim());
                    int minZ = Integer.parseInt(minZField.getText().trim());
                    int maxZ = Integer.parseInt(maxZField.getText().trim());

                    boolean checkGeneration = isSingleSeedPreciseGenerationCheckEffective();
                    searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight,
                            this::updateSearchProgress, this::addSearchResult, checkGeneration);

                    lastSearchThreadCount = threadCount;
                } else {
                    // 线程数没变化，直接恢复
                    searcher.resume();
                    isSearchPaused = false;
                }
                searchPauseButton.setText(getString("button.pause"));
                searchThreadCountField.setEnabled(false);
                return;
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this,
                        "线程数格式错误，无法继续",
                        "错误",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        try {
            // 验证种子
            String seedText = searchSeedField.getText().trim();
            if (seedText.isEmpty()) {
                JOptionPane.showMessageDialog(this, getString("error.seedRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 检查种子是否为整数
            double seedDouble;
            try {
                seedDouble = Double.parseDouble(seedText);
                if (seedDouble != Math.floor(seedDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.seedMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.seedFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 检查种子是否超过MC正常种子边界（绝对值超过2^63-1）
            long seed;
            try {
                seed = Long.parseLong(seedText);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.seedOutOfRange"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 验证线程数
            String threadText = searchThreadCountField.getText().trim();
            double threadDouble;
            try {
                threadDouble = Double.parseDouble(threadText);
                if (threadDouble != Math.floor(threadDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.threadCountMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.threadCountFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            int threadCount = (int) threadDouble;
            if (threadCount < 1) {
                JOptionPane.showMessageDialog(this, getString("error.threadCountRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 检查线程数是否超过CPU核数
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            if (threadCount > cpuThreads) {
                int result = JOptionPane.showConfirmDialog(
                        this,
                        getString("error.threadCountExceedsCPU", cpuThreads, cpuThreads),
                        getString("prompt.adjustThreadCount"),
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

            // 验证XZ坐标
            String minXText = minXField.getText().trim();
            String maxXText = maxXField.getText().trim();
            String minZText = minZField.getText().trim();
            String maxZText = maxZField.getText().trim();

            // 检查是否为整数
            double minXDouble, maxXDouble, minZDouble, maxZDouble;
            try {
                minXDouble = Double.parseDouble(minXText);
                if (minXDouble != Math.floor(minXDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.minXMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    minXField.setText(String.valueOf(DEFAULT_MIN_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.minXFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                minXField.setText(String.valueOf(DEFAULT_MIN_X));
                return;
            }

            try {
                maxXDouble = Double.parseDouble(maxXText);
                if (maxXDouble != Math.floor(maxXDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.maxXMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.maxXFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                return;
            }

            try {
                minZDouble = Double.parseDouble(minZText);
                if (minZDouble != Math.floor(minZDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.minZMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.minZFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                return;
            }

            try {
                maxZDouble = Double.parseDouble(maxZText);
                if (maxZDouble != Math.floor(maxZDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.maxZMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.maxZFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                return;
            }

            int minX = (int) minXDouble;
            int maxX = (int) maxXDouble;
            int minZ = (int) minZDouble;
            int maxZ = (int) maxZDouble;

            if (minX >= maxX) {
                JOptionPane.showMessageDialog(this, getString("error.minXGreaterThanMaxX"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (minZ >= maxZ) {
                JOptionPane.showMessageDialog(this, getString("error.minZGreaterThanMaxZ"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 检查世界边界：minX < -58594, maxX > 58593, minZ < -58594, maxZ > 58593
            boolean outOfBounds = minX < DEFAULT_MIN_X || maxX > DEFAULT_MAX_X || minZ < DEFAULT_MIN_Z || maxZ > DEFAULT_MAX_Z;

            if (outOfBounds) {
                int result = JOptionPane.showConfirmDialog(
                        this,
                        getString("error.outOfBounds"),
                        getString("prompt.warning"),
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (result != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            // 保存当前参数
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
            searchPauseButton.setText(getString("button.pause"));
            searchStopButton.setEnabled(true);
            searchResetButton.setEnabled(false);
            searchSeedField.setEnabled(false);
            searchThreadCountField.setEnabled(false); // 运行中不能修改，暂停时可以修改
            maxHeightComboBox.setEnabled(false);
            versionComboBox.setEnabled(false);
            worldPresetComboBox.setEnabled(false);
            applyBoundFieldEnableState(false);
            updateSingleSeedPreciseGenerationCheckUi();
            if (languageComboBox != null) {
                languageComboBox.setEnabled(false);
            }
            searchResultArea.setText("");
            searchProgressBar.setValue(0);
            searchProgressBar.setString(getString("progress.stage1", 0, 0, 0.0));
            searchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(0)));
            searchRemainingTimeLabel.setText(getString("remainingTime.calculating"));

            // 获取选择的版本
            GameVersion gameVersion = GameVersion.fromDisplayName((String) versionComboBox.getSelectedItem());
            WorldPresetMode worldPresetMode = getWorldPresetMode((String) worldPresetComboBox.getSelectedItem());

            searcher = new SearchCoords(gameVersion, worldPresetMode);
            boolean checkGeneration = isSingleSeedPreciseGenerationCheckEffective();
            searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight, this::updateSearchProgress, this::addSearchResult, checkGeneration);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, getString("error.invalidNumber"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleSearchPause() {
        if (searcher == null || !isSearchRunning) {
            return;
        }

        if (isSearchPaused) {
            // 恢复（线程数变化会在startSearch中处理）
            searcher.resume();
            isSearchPaused = false;
            searchPauseButton.setText(getString("button.pause"));
            searchThreadCountField.setEnabled(false); // 恢复后不能修改线程数
        } else {
            // 暂停
            searcher.pause();
            isSearchPaused = true;
            searchPauseButton.setText(getString("button.resume"));
            searchThreadCountField.setEnabled(true); // 暂停时可以修改线程数
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
        searchPauseButton.setText(getString("button.pause"));
        searchStopButton.setEnabled(false);
        searchResetButton.setEnabled(true);
        searchSeedField.setEnabled(true);
        searchThreadCountField.setEnabled(true);
        maxHeightComboBox.setEnabled(true);
        versionComboBox.setEnabled(true);
        worldPresetComboBox.setEnabled(true);
        applyBoundFieldEnableState(true);
        updateSingleSeedPreciseGenerationCheckUi();
        if (languageComboBox != null) {
            languageComboBox.setEnabled(true);
        }
        searchRemainingTimeLabel.setText(getString("remainingTime.stopped"));
    }

    private void resetSearchToDefaults() {
        long defaultSide = (long) DEFAULT_MAX_X - DEFAULT_MIN_X + 1;
        if (squareSideCheckBox != null) {
            squareSideCheckBox.setSelected(true);
        }
        if (squareSideField != null) {
            squareSideUpdating = true;
            squareSideField.setText(String.valueOf(defaultSide));
            squareSideUpdating = false;
        }
        minXField.setText(String.valueOf(DEFAULT_MIN_X));
        maxXField.setText(String.valueOf(DEFAULT_MAX_X));
        minZField.setText(String.valueOf(DEFAULT_MIN_Z));
        maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
        applyBoundFieldEnableState(!isSearchRunning);
        applySquareSideToBounds();
    }

    private void onSquareSideModeToggled() {
        applyBoundFieldEnableState(!isSearchRunning);
        if (squareSideCheckBox != null && squareSideCheckBox.isSelected()) {
            applySquareSideToBounds();
        }
    }

    private void applyBoundFieldEnableState(boolean allowEdit) {
        boolean squareMode = squareSideCheckBox != null && squareSideCheckBox.isSelected();
        if (squareSideCheckBox != null) {
            squareSideCheckBox.setEnabled(allowEdit);
        }
        if (squareSideField != null) {
            squareSideField.setEnabled(allowEdit && squareMode);
        }
        boolean boundsEditable = allowEdit && !squareMode;
        if (minXField != null) {
            minXField.setEnabled(boundsEditable);
        }
        if (maxXField != null) {
            maxXField.setEnabled(boundsEditable);
        }
        if (minZField != null) {
            minZField.setEnabled(boundsEditable);
        }
        if (maxZField != null) {
            maxZField.setEnabled(boundsEditable);
        }
    }

    /** Fill min/max from square side length centered at (0, 0). Side = max - min + 1. */
    private void applySquareSideToBounds() {
        if (squareSideUpdating || squareSideCheckBox == null || !squareSideCheckBox.isSelected()) {
            return;
        }
        if (squareSideField == null || minXField == null) {
            return;
        }
        String text = squareSideField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            long side = Long.parseLong(text);
            if (side < 1) {
                return;
            }
            long min = -side / 2;
            long max = min + side - 1;
            if (min < Integer.MIN_VALUE || max > Integer.MAX_VALUE) {
                return;
            }
            squareSideUpdating = true;
            minXField.setText(String.valueOf((int) min));
            maxXField.setText(String.valueOf((int) max));
            minZField.setText(String.valueOf((int) min));
            maxZField.setText(String.valueOf((int) max));
            squareSideUpdating = false;
            checkSearchParameterChange();
        } catch (NumberFormatException ignored) {
            // wait until the field holds a valid integer
        }
    }

    private void onListSquareSideModeToggled() {
        applyListBoundFieldEnableState(!isListSearchRunning);
        if (listSquareSideCheckBox != null && listSquareSideCheckBox.isSelected()) {
            applyListSquareSideToBounds();
        }
    }

    private void applyListBoundFieldEnableState(boolean allowEdit) {
        boolean squareMode = listSquareSideCheckBox != null && listSquareSideCheckBox.isSelected();
        if (listSquareSideCheckBox != null) {
            listSquareSideCheckBox.setEnabled(allowEdit);
        }
        if (listSquareSideField != null) {
            listSquareSideField.setEnabled(allowEdit && squareMode);
        }
        boolean boundsEditable = allowEdit && !squareMode;
        if (listMinXField != null) {
            listMinXField.setEnabled(boundsEditable);
        }
        if (listMaxXField != null) {
            listMaxXField.setEnabled(boundsEditable);
        }
        if (listMinZField != null) {
            listMinZField.setEnabled(boundsEditable);
        }
        if (listMaxZField != null) {
            listMaxZField.setEnabled(boundsEditable);
        }
    }

    private void applyListSquareSideToBounds() {
        if (listSquareSideUpdating || listSquareSideCheckBox == null || !listSquareSideCheckBox.isSelected()) {
            return;
        }
        if (listSquareSideField == null || listMinXField == null) {
            return;
        }
        String text = listSquareSideField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            long side = Long.parseLong(text);
            if (side < 1) {
                return;
            }
            long min = -side / 2;
            long max = min + side - 1;
            if (min < Integer.MIN_VALUE || max > Integer.MAX_VALUE) {
                return;
            }
            listSquareSideUpdating = true;
            listMinXField.setText(String.valueOf((int) min));
            listMaxXField.setText(String.valueOf((int) max));
            listMinZField.setText(String.valueOf((int) min));
            listMaxZField.setText(String.valueOf((int) max));
            listSquareSideUpdating = false;
            checkListSearchParameterChange();
        } catch (NumberFormatException ignored) {
            // wait until the field holds a valid integer
        }
    }

    // 创建从种子列表搜索面板
    private JPanel createListSearchPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 左侧：输入和进度
        JPanel leftPanel = new JPanel(new BorderLayout());

        // 输入区域
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Seed 文件选择
        gbc.gridx = 0;
        gbc.gridy = 0;
        listSearchSeedFileTitleLabel = new JLabel(getString("label.seedFile"));
        listSearchSeedFileTitleLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchSeedFileTitleLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        JPanel seedFilePanel = new JPanel(new BorderLayout());
        listSearchSeedFileButton = new JButton(getString("button.selectFile"));
        listSearchSeedFileButton.addActionListener(e -> selectSeedFile());
        listSearchSeedFileLabel = new JLabel(getString("label.noFileSelected"));
        listSearchSeedFileLabel.setFont(getLoadedFont());
        seedFilePanel.add(listSearchSeedFileButton, BorderLayout.WEST);
        seedFilePanel.add(listSearchSeedFileLabel, BorderLayout.CENTER);
        inputPanel.add(seedFilePanel, gbc);

        // Thread Count 输入
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchThreadCountLabel = new JLabel(getString("label.threadCount"));
        listSearchThreadCountLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchThreadCountLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listSearchThreadCountField = new JTextField(String.valueOf(Runtime.getRuntime().availableProcessors()), 20);
        listSearchThreadCountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listSearchThreadCountField, getString("label.threadCount").replace(":", ""));
            }
        });
        inputPanel.add(listSearchThreadCountField, gbc);

        // 高度筛选下拉框
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchHeightLabel = new JLabel(getString("label.heightFilter"));
        listSearchHeightLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchHeightLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] heightOptions = {"0", "-10", "-20", "-30", "-40", "-50", "-54"};
        listMaxHeightComboBox = new JComboBox<>(heightOptions);
        listMaxHeightComboBox.setSelectedIndex(4); // 默认选择 -40
        inputPanel.add(listMaxHeightComboBox, gbc);

        // 版本选择下拉框
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchVersionLabel = new JLabel(getString("label.version"));
        listSearchVersionLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchVersionLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] versionOptions = GameVersion.displayNames();
        listVersionComboBox = new JComboBox<>(versionOptions);
        listVersionComboBox.setSelectedIndex(0); // 默认选择 1.21~26.1
        inputPanel.add(listVersionComboBox, gbc);

        // 世界类型选择下拉框
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchWorldPresetLabel = new JLabel(getString("label.worldPreset"));
        listSearchWorldPresetLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchWorldPresetLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listWorldPresetComboBox = new JComboBox<>(getWorldPresetOptions());
        listWorldPresetComboBox.setSelectedIndex(0);
        listWorldPresetComboBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                updateListSearchPreciseGenerationCheckUi();
            }
        });
        inputPanel.add(listWorldPresetComboBox, gbc);

        // MinX 输入
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchMinXLabel = new JLabel(getString("label.minX"));
        listSearchMinXLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchMinXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMinXField = new JTextField(String.valueOf(DEFAULT_LIST_MIN_X), 20);
        listMinXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMinXField, getString("label.minX").replace(":", ""));
            }
        });
        inputPanel.add(listMinXField, gbc);

        // MaxX 输入
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchMaxXLabel = new JLabel(getString("label.maxX"));
        listSearchMaxXLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchMaxXLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMaxXField = new JTextField(String.valueOf(DEFAULT_LIST_MAX_X), 20);
        listMaxXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMaxXField, getString("label.maxX").replace(":", ""));
            }
        });
        inputPanel.add(listMaxXField, gbc);

        // MinZ 输入
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchMinZLabel = new JLabel(getString("label.minZ"));
        listSearchMinZLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchMinZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMinZField = new JTextField(String.valueOf(DEFAULT_LIST_MIN_Z), 20);
        listMinZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMinZField, getString("label.minZ").replace(":", ""));
            }
        });
        inputPanel.add(listMinZField, gbc);

        // MaxZ 输入
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchMaxZLabel = new JLabel(getString("label.maxZ"));
        listSearchMaxZLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchMaxZLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listMaxZField = new JTextField(String.valueOf(DEFAULT_LIST_MAX_Z), 20);
        listMaxZField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listMaxZField, getString("label.maxZ").replace(":", ""));
            }
        });
        inputPanel.add(listMaxZField, gbc);

        // 正方形区域边长（勾选后按中心 0,0 填入 min/max）
        gbc.gridx = 0;
        gbc.gridy = 9;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSquareSideCheckBox = new JCheckBox(getString("label.squareSide"));
        listSquareSideCheckBox.setFont(getLoadedFont());
        inputPanel.add(listSquareSideCheckBox, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        long listDefaultSide = (long) DEFAULT_LIST_MAX_X - DEFAULT_LIST_MIN_X + 1;
        listSquareSideField = new JTextField(String.valueOf(listDefaultSide), 20);
        listSquareSideCheckBox.setSelected(true);
        listSquareSideCheckBox.addActionListener(e -> onListSquareSideModeToggled());
        listSquareSideField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyListSquareSideToBounds();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyListSquareSideToBounds();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyListSquareSideToBounds();
            }
        });
        listSquareSideField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(listSquareSideField, getString("label.squareSide"));
            }
        });
        inputPanel.add(listSquareSideField, gbc);
        applyListBoundFieldEnableState(true);
        applyListSquareSideToBounds();

        // 精确检查生成情况复选框
        gbc.gridx = 0;
        gbc.gridy = 10;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        listSearchCheckGenerationLabel = new JLabel(getString("label.checkGeneration"));
        listSearchCheckGenerationLabel.setFont(getLoadedFont());
        inputPanel.add(listSearchCheckGenerationLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        listSearchCheckGenerationCheckBox = new JCheckBox();
        listSearchCheckGenerationCheckBox.setSelected(true); // 默认选中
        listSearchCheckGenerationCheckBox.setFont(getLoadedFont());
        inputPanel.add(listSearchCheckGenerationCheckBox, gbc);
        updateListSearchPreciseGenerationCheckUi();

        // 按钮区域
        JPanel buttonPanel = new JPanel(new FlowLayout());
        listSearchStartButton = new JButton(getString("button.startSearch"));
        listSearchPauseButton = new JButton(getString("button.pause"));
        listSearchStopButton = new JButton(getString("button.stop"));
        listSearchResetButton = new JButton(getString("button.resetList"));
        listSearchPauseButton.setEnabled(false);
        listSearchStopButton.setEnabled(false);
        buttonPanel.add(listSearchStartButton);
        buttonPanel.add(listSearchPauseButton);
        buttonPanel.add(listSearchStopButton);
        buttonPanel.add(listSearchResetButton);

        // 静态文字展示区域（放在按钮上方）
        JPanel creditPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        creditPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        listSearchCreditLabel = new JLabel(getString("credit.text"));
        listSearchCreditLabel.setFont(getLoadedFont());
        creditPanel.add(listSearchCreditLabel);

        // 将 credit 和按钮放在一个容器中，credit 在上，按钮在下
        JPanel creditButtonPanel = new JPanel(new BorderLayout());
        creditButtonPanel.add(creditPanel, BorderLayout.NORTH);
        creditButtonPanel.add(buttonPanel, BorderLayout.SOUTH);

        // 进度区域
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
        listSearchProgressBar.setString(getString("progress.total", 0, 0, 0.0));
        progressPanel.add(listSearchProgressBar, pgc);

        pgc.gridwidth = 1;
        pgc.gridy = 1;
        listSearchElapsedTimeLabel = new JLabel(getString("elapsedTime", formatTime(0)));
        progressPanel.add(listSearchElapsedTimeLabel, pgc);

        pgc.gridy = 2;
        listSearchCurrentSeedProgressLabel = new JLabel(getString("currentSeed.default"));
        listSearchCurrentSeedProgressLabel.setFont(getLoadedFont());
        progressPanel.add(listSearchCurrentSeedProgressLabel, pgc);

        pgc.gridy = 3;
        listSearchRemainingTimeLabel = new JLabel(getString("remainingTime.calculating"));
        progressPanel.add(listSearchRemainingTimeLabel, pgc);

        leftPanel.add(inputPanel, BorderLayout.NORTH);
        leftPanel.add(creditButtonPanel, BorderLayout.CENTER);

        // 将进度区域放在另一个容器中
        JPanel leftBottomPanel = new JPanel(new BorderLayout());
        leftBottomPanel.add(progressPanel, BorderLayout.CENTER);

        JPanel leftContainer = new JPanel(new BorderLayout());
        leftContainer.add(leftPanel, BorderLayout.CENTER);
        leftContainer.add(leftBottomPanel, BorderLayout.SOUTH);

        // 右侧：结果显示
        listSearchRightPanel = new JPanel(new BorderLayout());
        listSearchRightPanel.setBorder(BorderFactory.createTitledBorder(getString("result.border")));
        listSearchResultArea = new JTextArea();
        listSearchResultArea.setEditable(false);
        listSearchResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(listSearchResultArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel exportPanel = new JPanel(new FlowLayout());
        listSearchExportButton = new JButton(getString("button.export"));
        listSearchExportButton.addActionListener(e -> exportListSearchResults());
        listSearchExportSeedListButton = new JButton(getString("button.exportSeedList"));
        listSearchExportSeedListButton.addActionListener(e -> exportSeedList());
        listSortByYButton = new JButton(getString("button.sortByY"));
        listSortByYButton.addActionListener(e -> sortListByLowestY());
        listSortByDistanceButton = new JButton(getString("button.sortByDistance"));
        listSortByDistanceButton.addActionListener(e -> sortListByDistance());
        exportPanel.add(listSearchExportButton);
        exportPanel.add(listSearchExportSeedListButton);
        exportPanel.add(listSortByYButton);
        exportPanel.add(listSortByDistanceButton);
        listSearchRightPanel.add(scrollPane, BorderLayout.CENTER);
        listSearchRightPanel.add(exportPanel, BorderLayout.SOUTH);

        // 使用 JSplitPane 分割
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftContainer, listSearchRightPanel);
        splitPane.setDividerLocation(600);
        splitPane.setResizeWeight(0.5);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 添加事件监听
        listSearchStartButton.addActionListener(e -> startListSearch());
        listSearchPauseButton.addActionListener(e -> toggleListSearchPause());
        listSearchStopButton.addActionListener(e -> stopListSearch());
        listSearchResetButton.addActionListener(e -> resetListSearchToDefaults());

        // 添加输入字段监听，检测参数变化
        addListSearchParameterListeners();

        return mainPanel;
    }

    private void updateSearchProgress(SearchCoords.ProgressInfo info) {
        SwingUtilities.invokeLater(() -> {
            if (!isSearchRunning) return;

            int progress = (int) Math.min(100, info.percentage());
            searchProgressBar.setValue(progress);
            String progressKey = info.stage() == 1 ? "progress.stage1" : "progress.stage2";
            searchProgressBar.setString(getString(progressKey, info.processed(), info.total(), info.percentage()));

            // 暂停时不更新时间
            if (!isSearchPaused) {
                searchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(info.elapsedMs())));
                if (info.remainingMs() > 0) {
                    searchRemainingTimeLabel.setText(getString("remainingTime", formatTime(info.remainingMs())));
                } else {
                    searchRemainingTimeLabel.setText(getString("remainingTime.calculating"));
                }
            } else {
                searchRemainingTimeLabel.setText(getString("remainingTime.paused"));
            }

            // 仅阶段2完成时结束搜索（阶段1达到100%不解锁 UI）
            if (info.stage() == 2 && info.processed() >= info.total()) {
                isSearchRunning = false;
                isSearchPaused = false;
                searchStartButton.setEnabled(true);
                searchPauseButton.setEnabled(false);
                searchPauseButton.setText(getString("button.pause"));
                searchStopButton.setEnabled(false);
                searchResetButton.setEnabled(true);
                searchSeedField.setEnabled(true);
                searchThreadCountField.setEnabled(true);
                maxHeightComboBox.setEnabled(true);
                versionComboBox.setEnabled(true);
                worldPresetComboBox.setEnabled(true);
                applyBoundFieldEnableState(true);
                updateSingleSeedPreciseGenerationCheckUi();
                if (languageComboBox != null) {
                    languageComboBox.setEnabled(true);
                }
                // 不再弹框，只在进度条中显示完成
                searchProgressBar.setString(getString("progress.complete", info.processed(), info.total()));
                searchRemainingTimeLabel.setText(getString("remainingTime.completed"));
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
        String resultText = searchResultArea.getText();
        if (resultText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, getString("error.noResultsToExport"), getString("prompt.information"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(getString("dialog.exportResults"));
        fileChooser.setFileFilter(new FileNameExtensionFilter(getString("dialog.textFiles"), "txt"));
        fileChooser.setSelectedFile(new File(getString("file.searchOutput")));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                // 导出所有结果，包括带有"x"标记的无法生成的结果
                writer.print(resultText);
                JOptionPane.showMessageDialog(this, getString("success.export"), getString("prompt.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, getString("error.exportFailed", e.getMessage()), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    private String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return getString("time.format", days, hours, minutes, seconds);
    }
    /**
     * 设置窗口图标
     * 图标文件应放在 src/main/resources/icon.png 或 icon.ico
     */
    private void setWindowIcon() {
        try {
            // 尝试从资源文件加载图标
            java.net.URL iconURL = getClass().getResource("/icon.png");
            if (iconURL == null) {
                iconURL = getClass().getResource("/icon.ico");
            }
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            } else {
                // 如果没有找到图标文件，可以创建一个简单的默认图标
                // 或者使用系统默认图标（不设置）
                System.out.println("提示: 未找到图标文件 (icon.png 或 icon.ico)，使用系统默认图标");
            }
        } catch (Exception e) {
            System.err.println("设置图标时出错: " + e.getMessage());
        }
    }

    /**
     * 设置字体
     * 从资源文件加载 font.ttf 字体
     */
    private void setChineseFont() {
        try {
            // 从资源文件加载 font.ttf
            java.io.InputStream fontStream = getClass().getResourceAsStream("/font.ttf");
            if (fontStream == null) {
                System.err.println("错误: 未找到字体文件 font.ttf，请确保文件位于 src/main/resources/font.ttf");
                return;
            }

            // 创建字体
            Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
            fontStream.close();

            // 注册字体到系统
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(font);

            // 创建指定大小的字体并保存
            loadedFont = font.deriveFont(Font.PLAIN, 12f);

            // 设置全局字体
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

            System.out.println("成功加载字体: " + font.getFontName() + " (大小: " + loadedFont.getSize() + ")");
        } catch (FontFormatException e) {
            System.err.println("字体文件格式错误: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("读取字体文件时出错: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("加载字体时出错: " + e.getMessage());
        }
    }

    /**
     * 获取加载的字体，如果未加载则返回默认字体
     */
    private Font getLoadedFont() {
        if (loadedFont != null) {
            return loadedFont;
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    private String[] getWorldPresetOptions() {
        return new String[]{getString("worldPreset.normal"), getString("worldPreset.largeBiomes"), getString("worldPreset.singleBiome")};
    }

    private WorldPresetMode getWorldPresetMode(String presetLabel) {
        if (presetLabel != null && presetLabel.equals(getString("worldPreset.largeBiomes"))) {
            return WorldPresetMode.LARGE_BIOMES;
        }
        if (presetLabel != null && presetLabel.equals(getString("worldPreset.singleBiome"))) {
            return WorldPresetMode.SINGLE_BIOME;
        }
        return WorldPresetMode.NORMAL;
    }

    private void updateSingleSeedPreciseGenerationCheckUi() {
        if (searchCheckGenerationCheckBox == null || worldPresetComboBox == null) {
            return;
        }
        boolean singleBiome = getWorldPresetMode((String) worldPresetComboBox.getSelectedItem()) == WorldPresetMode.SINGLE_BIOME;
        if (singleBiome) {
            searchCheckGenerationCheckBox.setSelected(false);
            searchCheckGenerationCheckBox.setEnabled(false);
            if (searchCheckGenerationLabel != null) {
                searchCheckGenerationLabel.setEnabled(false);
            }
        } else {
            if (searchCheckGenerationLabel != null) {
                searchCheckGenerationLabel.setEnabled(true);
            }
            searchCheckGenerationCheckBox.setEnabled(!isSearchRunning);
        }
    }

    private void updateListSearchPreciseGenerationCheckUi() {
        if (listSearchCheckGenerationCheckBox == null || listWorldPresetComboBox == null) {
            return;
        }
        boolean singleBiome = getWorldPresetMode((String) listWorldPresetComboBox.getSelectedItem()) == WorldPresetMode.SINGLE_BIOME;
        if (singleBiome) {
            listSearchCheckGenerationCheckBox.setSelected(false);
            listSearchCheckGenerationCheckBox.setEnabled(false);
            if (listSearchCheckGenerationLabel != null) {
                listSearchCheckGenerationLabel.setEnabled(false);
            }
        } else {
            if (listSearchCheckGenerationLabel != null) {
                listSearchCheckGenerationLabel.setEnabled(true);
            }
            listSearchCheckGenerationCheckBox.setEnabled(!isListSearchRunning);
        }
    }

    private boolean isSingleSeedPreciseGenerationCheckEffective() {
        if (worldPresetComboBox == null) {
            return false;
        }
        if (getWorldPresetMode((String) worldPresetComboBox.getSelectedItem()) == WorldPresetMode.SINGLE_BIOME) {
            return false;
        }
        return searchCheckGenerationCheckBox != null && searchCheckGenerationCheckBox.isSelected();
    }

    private boolean isListSearchPreciseGenerationCheckEffective() {
        if (listWorldPresetComboBox == null) {
            return false;
        }
        if (getWorldPresetMode((String) listWorldPresetComboBox.getSelectedItem()) == WorldPresetMode.SINGLE_BIOME) {
            return false;
        }
        return listSearchCheckGenerationCheckBox != null && listSearchCheckGenerationCheckBox.isSelected();
    }

    /**
     * 切换语言
     */
    private void changeLanguage() {
        // 如果正在搜索或暂停，不允许切换语言
        if (isSearchRunning || isSearchPaused || isListSearchRunning || isListSearchPaused) {
            // 恢复原来的选择
            if (languageComboBox != null) {
                if (currentLocale.getLanguage().equals("zh")) {
                    languageComboBox.setSelectedIndex(0);
                } else {
                    languageComboBox.setSelectedIndex(1);
                }
            }
            return;
        }

        // 获取选择的语言（从第一个tab的语言下拉框获取）
        String selectedLanguage = null;
        if (languageComboBox != null) {
            selectedLanguage = (String) languageComboBox.getSelectedItem();
        }

        if (selectedLanguage == null) {
            return;
        }

        // 根据选择设置Locale
        Locale newLocale;
        if ("中文".equals(selectedLanguage)) {
            newLocale = new Locale("zh", "CN");
        } else {
            newLocale = new Locale("en", "US");
        }

        // 如果语言没有变化，不执行切换
        if (newLocale.equals(currentLocale)) {
            return;
        }

        // 重新加载ResourceBundle
        try {
            currentLocale = newLocale;
            messages = ResourceBundle.getBundle("messages", currentLocale);
        } catch (Exception e) {
            System.err.println("加载语言资源失败: " + e.getMessage());
            return;
        }
        // 更新所有UI文本
        updateUITexts();
    }

    /**
     * 更新所有UI文本
     */
    private void updateUITexts() {
        SwingUtilities.invokeLater(() -> {
            // 更新窗口标题
            setTitle(getString("window.title"));

            // 更新标签页标题
            JTabbedPane tabbedPane = (JTabbedPane) getContentPane().getComponent(0);
            if (tabbedPane != null) {
                tabbedPane.setTitleAt(0, getString("tab.singleSeedSearch"));
                tabbedPane.setTitleAt(1, getString("tab.listSearch"));
            }

            // 更新单种子搜索面板的所有文本
            updateSingleSeedSearchTexts();

            // 更新列表搜索面板的所有文本
            updateListSearchTexts();
        });
    }

    /**
     * 更新单种子搜索面板的文本
     */
    private void updateSingleSeedSearchTexts() {
        // 更新所有标签文本
        if (searchSeedLabel != null) {
            searchSeedLabel.setText(getString("label.seed"));
        }
        if (searchThreadCountLabel != null) {
            searchThreadCountLabel.setText(getString("label.threadCount"));
        }
        if (searchHeightLabel != null) {
            searchHeightLabel.setText(getString("label.heightFilter"));
        }
        if (searchVersionLabel != null) {
            searchVersionLabel.setText(getString("label.version"));
        }
        if (searchWorldPresetLabel != null) {
            searchWorldPresetLabel.setText(getString("label.worldPreset"));
        }
        if (searchMinXLabel != null) {
            searchMinXLabel.setText(getString("label.minX"));
        }
        if (searchMaxXLabel != null) {
            searchMaxXLabel.setText(getString("label.maxX"));
        }
        if (searchMinZLabel != null) {
            searchMinZLabel.setText(getString("label.minZ"));
        }
        if (searchMaxZLabel != null) {
            searchMaxZLabel.setText(getString("label.maxZ"));
        }
        if (squareSideCheckBox != null) {
            squareSideCheckBox.setText(getString("label.squareSide"));
        }
        if (searchCheckGenerationLabel != null) {
            searchCheckGenerationLabel.setText(getString("label.checkGeneration"));
        }
        if (searchLanguageLabel != null) {
            searchLanguageLabel.setText(getString("label.language"));
        }

        // 更新credit文本
        if (searchCreditLabel != null) {
            searchCreditLabel.setText(getString("credit.text"));
        }

        // 更新右侧面板边框
        if (searchRightPanel != null) {
            searchRightPanel.setBorder(BorderFactory.createTitledBorder(getString("result.border")));
        }

        // 更新按钮文本
        if (searchStartButton != null) {
            searchStartButton.setText(getString("button.startSearch"));
        }
        if (searchPauseButton != null) {
            searchPauseButton.setText(isSearchPaused ? getString("button.resume") : getString("button.pause"));
        }
        if (searchStopButton != null) {
            searchStopButton.setText(getString("button.stop"));
        }
        if (searchResetButton != null) {
            searchResetButton.setText(getString("button.reset"));
        }
        if (searchExportButton != null) {
            searchExportButton.setText(getString("button.export"));
        }
        if (searchSortButton != null) {
            searchSortButton.setText(getString("button.sort"));
        }
        if (worldPresetComboBox != null) {
            int selectedIndex = worldPresetComboBox.getSelectedIndex();
            worldPresetComboBox.setModel(new DefaultComboBoxModel<>(getWorldPresetOptions()));
            worldPresetComboBox.setSelectedIndex(Math.max(0, selectedIndex));
        }
        updateSingleSeedPreciseGenerationCheckUi();

        // 更新进度条和标签
        if (searchProgressBar != null && !isSearchRunning) {
            searchProgressBar.setString(getString("progress.format", 0, 0, 0.0));
        }
        if (searchElapsedTimeLabel != null && !isSearchRunning) {
            searchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(0)));
        }
        if (searchRemainingTimeLabel != null && !isSearchRunning) {
            searchRemainingTimeLabel.setText(getString("remainingTime.calculating"));
        }
    }
    /**
     * 更新列表搜索面板的文本
     */
    private void updateListSearchTexts() {
        // 更新所有标签文本
        if (listSearchSeedFileTitleLabel != null) {
            listSearchSeedFileTitleLabel.setText(getString("label.seedFile"));
        }
        if (listSearchThreadCountLabel != null) {
            listSearchThreadCountLabel.setText(getString("label.threadCount"));
        }
        if (listSearchHeightLabel != null) {
            listSearchHeightLabel.setText(getString("label.heightFilter"));
        }
        if (listSearchVersionLabel != null) {
            listSearchVersionLabel.setText(getString("label.version"));
        }
        if (listSearchWorldPresetLabel != null) {
            listSearchWorldPresetLabel.setText(getString("label.worldPreset"));
        }
        if (listSearchMinXLabel != null) {
            listSearchMinXLabel.setText(getString("label.minX"));
        }
        if (listSearchMaxXLabel != null) {
            listSearchMaxXLabel.setText(getString("label.maxX"));
        }
        if (listSearchMinZLabel != null) {
            listSearchMinZLabel.setText(getString("label.minZ"));
        }
        if (listSearchMaxZLabel != null) {
            listSearchMaxZLabel.setText(getString("label.maxZ"));
        }
        if (listSquareSideCheckBox != null) {
            listSquareSideCheckBox.setText(getString("label.squareSide"));
        }
        if (listSearchCheckGenerationLabel != null) {
            listSearchCheckGenerationLabel.setText(getString("label.checkGeneration"));
        }

        // 更新按钮文本
        if (listSearchStartButton != null) {
            listSearchStartButton.setText(getString("button.startSearch"));
        }
        if (listSearchPauseButton != null) {
            listSearchPauseButton.setText(isListSearchPaused ? getString("button.resume") : getString("button.pause"));
        }
        if (listSearchStopButton != null) {
            listSearchStopButton.setText(getString("button.stop"));
        }
        if (listSearchResetButton != null) {
            listSearchResetButton.setText(getString("button.resetList"));
        }
        if (listSearchExportButton != null) {
            listSearchExportButton.setText(getString("button.export"));
        }
        if (listSearchExportSeedListButton != null) {
            listSearchExportSeedListButton.setText(getString("button.exportSeedList"));
        }
        if (listSortByYButton != null) {
            listSortByYButton.setText(getString("button.sortByY"));
        }
        if (listSortByDistanceButton != null) {
            listSortByDistanceButton.setText(getString("button.sortByDistance"));
        }
        if (listWorldPresetComboBox != null) {
            int selectedIndex = listWorldPresetComboBox.getSelectedIndex();
            listWorldPresetComboBox.setModel(new DefaultComboBoxModel<>(getWorldPresetOptions()));
            listWorldPresetComboBox.setSelectedIndex(Math.max(0, selectedIndex));
        }
        updateListSearchPreciseGenerationCheckUi();
        if (listSearchSeedFileButton != null) {
            listSearchSeedFileButton.setText(getString("button.selectFile"));
        }

        // 更新种子文件标签
        if (listSearchSeedFileLabel != null) {
            if (selectedSeedFile != null && selectedSeedFile.exists()) {
                listSearchSeedFileLabel.setText(selectedSeedFile.getName());
            } else {
                listSearchSeedFileLabel.setText(getString("label.noFileSelected"));
            }
        }

        // 更新进度条和标签
        if (listSearchProgressBar != null && !isListSearchRunning) {
            listSearchProgressBar.setString(getString("progress.total", 0, 0, 0.0));
        }
        if (listSearchElapsedTimeLabel != null && !isListSearchRunning) {
            listSearchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(0)));
        }
        if (listSearchRemainingTimeLabel != null && !isListSearchRunning) {
            listSearchRemainingTimeLabel.setText(getString("remainingTime.calculating"));
        }
        if (listSearchCurrentSeedProgressLabel != null && !isListSearchRunning) {
            listSearchCurrentSeedProgressLabel.setText(getString("currentSeed.default"));
        }

        // 更新credit文本
        if (listSearchCreditLabel != null) {
            listSearchCreditLabel.setText(getString("credit.text"));
        }

        // 更新右侧面板边框
        if (listSearchRightPanel != null) {
            listSearchRightPanel.setBorder(BorderFactory.createTitledBorder(getString("result.border")));
        }
    }

    // ========== 从种子列表搜索相关方法 ==========

    // 添加搜索参数监听器，检测参数变化（不包括线程数）
    private void addListSearchParameterListeners() {
        // 高度筛选变化监听
        listMaxHeightComboBox.addActionListener(e -> checkListSearchParameterChange());

        // 坐标变化监听
        listMinXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }
        });
        listMaxXField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }
        });
        listMinZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }
        });
        listMaxZField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                checkListSearchParameterChange();
            }
        });
    }

    // 检查搜索参数是否变化（除了线程数）
    private void checkListSearchParameterChange() {
        if (isListSearchRunning && !isListSearchPaused) {
            return; // 运行中且未暂停，不检查
        }

        if (!isListSearchPaused) {
            return; // 未暂停，不检查
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

            // 如果参数变化且处于暂停状态，重置进度（线程数变化不触发重置）
            if (minX != lastListSearchMinX ||
                    maxX != lastListSearchMaxX || minZ != lastListSearchMinZ || maxZ != lastListSearchMaxZ ||
                    maxHeight != lastListSearchMaxHeight) {
                // 停止当前搜索
                stopAllListSearchers();
                if (listSearchExecutor != null) {
                    listSearchExecutor.shutdownNow();
                }
                isListSearchRunning = false;
                isListSearchPaused = false;
                listSearchStartButton.setEnabled(true);
                listSearchPauseButton.setEnabled(false);
                listSearchPauseButton.setText(getString("button.pause"));
                listSearchStopButton.setEnabled(false);
                listSearchResetButton.setEnabled(true);
                listSearchSeedFileButton.setEnabled(true);
                listSearchThreadCountField.setEnabled(true);
                listMaxHeightComboBox.setEnabled(true);
                listVersionComboBox.setEnabled(true);
                listWorldPresetComboBox.setEnabled(true);
                applyListBoundFieldEnableState(true);
                updateListSearchPreciseGenerationCheckUi();
                listSearchResultArea.setText("");
                listSearchProgressBar.setValue(0);
                listSearchProgressBar.setString(getString("progress.total", 0, 0, 0.0));
                listSearchCurrentSeedProgressLabel.setText(getString("currentSeed.default"));
                listSearchRemainingTimeLabel.setText(getString("remainingTime.reset"));
            }
        } catch (NumberFormatException e) {
            // 忽略无效输入
        }
    }

    // 选择种子文件
    private void selectSeedFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(getString("dialog.selectSeedFile"));
        fileChooser.setFileFilter(new FileNameExtensionFilter(getString("dialog.textFiles"), "txt"));

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            selectedSeedFile = fileChooser.getSelectedFile();
            listSearchSeedFileLabel.setText(selectedSeedFile.getName());
        }
    }

    /** 仅统计有效种子行数，不把种子载入内存 */
    private long countValidSeeds(File file) throws IOException {
        long count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file), 1 << 20)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!isListSearchRunning) {
                    break;
                }
                if (parseSeedLine(line) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /** @return 解析成功的种子，无效行返回 null */
    private static Long parseSeedLine(String line) {
        if (line == null) {
            return null;
        }
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void restoreListSearchUiIdle() {
        isListSearchRunning = false;
        isListSearchPaused = false;
        listSearchStartButton.setEnabled(true);
        listSearchPauseButton.setEnabled(false);
        listSearchPauseButton.setText(getString("button.pause"));
        listSearchStopButton.setEnabled(false);
        listSearchResetButton.setEnabled(true);
        listSearchSeedFileButton.setEnabled(true);
        listSearchThreadCountField.setEnabled(true);
        listMaxHeightComboBox.setEnabled(true);
        listVersionComboBox.setEnabled(true);
        listWorldPresetComboBox.setEnabled(true);
        applyListBoundFieldEnableState(true);
        updateListSearchPreciseGenerationCheckUi();
    }

    private void failListSearch(String message) {
        SwingUtilities.invokeLater(() -> {
            restoreListSearchUiIdle();
            listSearchCurrentSeedProgressLabel.setText(getString("currentSeed.default"));
            listSearchRemainingTimeLabel.setText(getString("remainingTime.stopped"));
            JOptionPane.showMessageDialog(this, message, getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
        });
    }

    // 搜索相关方法
    private void startListSearch() {
        // 如果当前处于暂停状态，直接恢复（不重新开始）
        if (isListSearchRunning && isListSearchPaused) {
            try {
                String threadText = listSearchThreadCountField.getText().trim();
                int threadCount = Integer.parseInt(threadText);
                if (threadCount < 1) {
                    JOptionPane.showMessageDialog(this, getString("error.threadCountRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                int cpuThreads = Runtime.getRuntime().availableProcessors();
                if (threadCount > cpuThreads) {
                    int result = JOptionPane.showConfirmDialog(
                            this,
                            getString("error.threadCountExceedsCPU", cpuThreads, cpuThreads),
                            getString("prompt.adjustThreadCount"),
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

                if (threadCount != lastListSearchThreadCount) {
                    long area = (long) (lastListSearchMaxX - lastListSearchMinX)
                            * (lastListSearchMaxZ - lastListSearchMinZ);
                    int concurrentSeeds = computeConcurrentSeeds(area, threadCount);
                    int threadsPerSeed = computeThreadsPerSeed(threadCount, concurrentSeeds);
                    lastListConcurrentSeeds = concurrentSeeds;
                    if (concurrentSeeds > 1 && listSearchExecutor != null) {
                        listSearchExecutor.setCorePoolSize(concurrentSeeds);
                        listSearchExecutor.setMaximumPoolSize(concurrentSeeds);
                    } else {
                        boolean checkGeneration = isListSearchPreciseGenerationCheckEffective();
                        synchronized (activeListSearchers) {
                            for (SearchCoords s : activeListSearchers) {
                                s.startSearch(currentListSeed, threadsPerSeed,
                                        lastListSearchMinX, lastListSearchMaxX,
                                        lastListSearchMinZ, lastListSearchMaxZ,
                                        lastListSearchMaxHeight,
                                        null, r -> {
                                        }, checkGeneration);
                            }
                        }
                    }
                    lastListSearchThreadCount = threadCount;
                }

                synchronized (activeListSearchers) {
                    for (SearchCoords s : activeListSearchers) {
                        s.resume();
                    }
                }
                isListSearchPaused = false;
                listSearchPauseButton.setText(getString("button.pause"));
                listSearchThreadCountField.setEnabled(false);
                return;
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this,
                        getString("error.threadCountFormatErrorContinue"),
                        getString("prompt.error"),
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        try {
            // 验证种子文件（完整解析放到后台，避免 EDT 上加载百万种子 OOM 闪退）
            if (selectedSeedFile == null || !selectedSeedFile.exists()) {
                JOptionPane.showMessageDialog(this, getString("error.seedFileRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            final File seedFile = selectedSeedFile;

            // 验证线程数
            String threadText = listSearchThreadCountField.getText().trim();
            double threadDouble;
            try {
                threadDouble = Double.parseDouble(threadText);
                if (threadDouble != Math.floor(threadDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.threadCountMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.threadCountFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            int threadCount = (int) threadDouble;
            if (threadCount < 1) {
                JOptionPane.showMessageDialog(this, getString("error.threadCountRequired"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            int cpuThreads = Runtime.getRuntime().availableProcessors();
            if (threadCount > cpuThreads) {
                int result = JOptionPane.showConfirmDialog(
                        this,
                        getString("error.threadCountExceedsCPU", cpuThreads, cpuThreads),
                        getString("prompt.adjustThreadCount"),
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

            String minXText = listMinXField.getText().trim();
            String maxXText = listMaxXField.getText().trim();
            String minZText = listMinZField.getText().trim();
            String maxZText = listMaxZField.getText().trim();

            double minXDouble, maxXDouble, minZDouble, maxZDouble;
            try {
                minXDouble = Double.parseDouble(minXText);
                if (minXDouble != Math.floor(minXDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.minXMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.minXFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
                return;
            }

            try {
                maxXDouble = Double.parseDouble(maxXText);
                if (maxXDouble != Math.floor(maxXDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.maxXMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.maxXFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
                return;
            }

            try {
                minZDouble = Double.parseDouble(minZText);
                if (minZDouble != Math.floor(minZDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.minZMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.minZFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
                return;
            }

            try {
                maxZDouble = Double.parseDouble(maxZText);
                if (maxZDouble != Math.floor(maxZDouble)) {
                    JOptionPane.showMessageDialog(this, getString("error.maxZMustBeInteger"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                    listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, getString("error.maxZFormatError"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
                return;
            }

            int minX = (int) minXDouble;
            int maxX = (int) maxXDouble;
            int minZ = (int) minZDouble;
            int maxZ = (int) maxZDouble;

            if (minX >= maxX) {
                JOptionPane.showMessageDialog(this, getString("error.minXGreaterThanMaxX"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (minZ >= maxZ) {
                JOptionPane.showMessageDialog(this, getString("error.minZGreaterThanMaxZ"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
                return;
            }

            long area = (long) (maxX - minX) * (maxZ - minZ);
            int concurrentSeeds = computeConcurrentSeeds(area, threadCount);
            int threadsPerSeed = computeThreadsPerSeed(threadCount, concurrentSeeds);

            lastListSearchMinX = minX;
            lastListSearchMaxX = maxX;
            lastListSearchMinZ = minZ;
            lastListSearchMaxZ = maxZ;
            lastListSearchMaxHeight = maxHeight;
            lastListSearchThreadCount = threadCount;
            lastListConcurrentSeeds = concurrentSeeds;

            isListSearchRunning = true;
            isListSearchPaused = false;
            listSearchStartButton.setEnabled(false);
            listSearchPauseButton.setEnabled(true);
            listSearchPauseButton.setText(getString("button.pause"));
            listSearchStopButton.setEnabled(true);
            listSearchResetButton.setEnabled(false);
            listSearchSeedFileButton.setEnabled(false);
            listSearchThreadCountField.setEnabled(false);
            listMaxHeightComboBox.setEnabled(false);
            listVersionComboBox.setEnabled(false);
            listWorldPresetComboBox.setEnabled(false);
            applyListBoundFieldEnableState(false);
            updateListSearchPreciseGenerationCheckUi();
            listSearchResultArea.setText("");
            listSearchProgressBar.setValue(0);
            listSearchProgressBar.setString(getString("progress.countingSeeds"));
            listSearchCurrentSeedProgressLabel.setText(getString("progress.countingSeeds"));
            listSearchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(0)));
            listSearchRemainingTimeLabel.setText(getString("remainingTime.calculating"));

            seedResults.clear();
            listProcessedSeedsRef.set(0);
            activeListSearchers.clear();

            GameVersion gameVersion = GameVersion.fromDisplayName((String) listVersionComboBox.getSelectedItem());
            WorldPresetMode worldPresetMode = getWorldPresetMode((String) listWorldPresetComboBox.getSelectedItem());

            final int finalConcurrentSeeds = concurrentSeeds;
            final int finalThreadsPerSeed = threadsPerSeed;
            final boolean seedParallel = concurrentSeeds > 1;
            final long startTime = System.currentTimeMillis();
            final long[] pausedTimeRef = {0};
            final long[] pauseStartTimeRef = {0};

            new Thread(() -> {
                final long totalSeeds;
                try {
                    totalSeeds = countValidSeeds(seedFile);
                } catch (OutOfMemoryError e) {
                    failListSearch(getString("error.outOfMemory"));
                    return;
                } catch (IOException e) {
                    failListSearch(getString("error.seedFileReadFailed", e.getMessage()));
                    return;
                } catch (Throwable t) {
                    failListSearch(getString("error.listSearchFailed", t.toString()));
                    return;
                }

                if (!isListSearchRunning) {
                    return;
                }
                if (totalSeeds <= 0) {
                    failListSearch(getString("error.seedFileEmpty"));
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    if (!isListSearchRunning) {
                        return;
                    }
                    listSearchProgressBar.setMaximum((int) Math.min(totalSeeds, Integer.MAX_VALUE));
                    listSearchProgressBar.setValue(0);
                    listSearchProgressBar.setString(getString("progress.total", 0, totalSeeds, 0.0));
                    listSearchCurrentSeedProgressLabel.setText(
                            seedParallel
                                    ? getString("currentSeed.concurrent", finalConcurrentSeeds)
                                    : getString("currentSeed.default"));
                });

                Thread progressMonitorThread = new Thread(() -> {
                    while (isListSearchRunning) {
                        try {
                            Thread.sleep(100);

                            if (isListSearchPaused) {
                                if (pauseStartTimeRef[0] == 0) {
                                    pauseStartTimeRef[0] = System.currentTimeMillis();
                                }
                            } else {
                                if (pauseStartTimeRef[0] > 0) {
                                    pausedTimeRef[0] += System.currentTimeMillis() - pauseStartTimeRef[0];
                                    pauseStartTimeRef[0] = 0;
                                }
                            }

                            long currentPausedTime = pausedTimeRef[0];
                            if (pauseStartTimeRef[0] > 0) {
                                currentPausedTime += System.currentTimeMillis() - pauseStartTimeRef[0];
                            }
                            final long elapsedMs = System.currentTimeMillis() - startTime - currentPausedTime;
                            final int currentProgress = listProcessedSeedsRef.get();

                            SwingUtilities.invokeLater(() -> {
                                if (currentProgress > 0 && currentProgress < totalSeeds) {
                                    if (isListSearchPaused) {
                                        return;
                                    }
                                    final long remainingMs = elapsedMs > 0
                                            ? (elapsedMs * (totalSeeds - currentProgress) / currentProgress) : 0;
                                    listSearchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(elapsedMs)));
                                    if (remainingMs > 0) {
                                        listSearchRemainingTimeLabel.setText(getString("remainingTime", formatTime(remainingMs)));
                                    } else {
                                        listSearchRemainingTimeLabel.setText(getString("remainingTime.calculating"));
                                    }
                                }
                            });
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
                progressMonitorThread.setDaemon(true);
                progressMonitorThread.start();

                try {
                    runListSeedSearch(seedFile, totalSeeds, finalConcurrentSeeds, finalThreadsPerSeed, seedParallel,
                            gameVersion, worldPresetMode, minX, maxX, minZ, maxZ, maxHeight,
                            startTime, pausedTimeRef, pauseStartTimeRef);
                } catch (OutOfMemoryError e) {
                    stopAllListSearchers();
                    if (listSearchExecutor != null) {
                        listSearchExecutor.shutdownNow();
                    }
                    failListSearch(getString("error.outOfMemory"));
                } catch (Throwable t) {
                    stopAllListSearchers();
                    if (listSearchExecutor != null) {
                        listSearchExecutor.shutdownNow();
                    }
                    failListSearch(getString("error.listSearchFailed", t.toString()));
                }
            }, "ListSearch-Main").start();

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, getString("error.invalidNumber"), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 流式分段读取种子文件并搜索：内存中仅保留在途任务（Semaphore 限制），不缓存全部种子。
     */
    private void runListSeedSearch(File seedFile, long totalSeeds, int finalConcurrentSeeds, int finalThreadsPerSeed,
                                   boolean seedParallel, GameVersion gameVersion, WorldPresetMode worldPresetMode,
                                   int minX, int maxX, int minZ, int maxZ, double maxHeight,
                                   long startTime, long[] pausedTimeRef, long[] pauseStartTimeRef) throws IOException {
        listSearchExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(finalConcurrentSeeds);
        final long[] lastTotalProgressUpdateTime = {0};
        final long[] lastSeedProgressUpdateTime = {0};
        final long[] lastResultUiFlushTime = {0};
        final long SEED_PROGRESS_UPDATE_INTERVAL_MS = 100;
        final StringBuilder resultUiBuffer = new StringBuilder(4096);
        final Object resultUiLock = new Object();
        Semaphore submissionThrottle = new Semaphore(Math.max(2, finalConcurrentSeeds * 2));

        Runnable flushResultUi = () -> {
            final String chunk;
            synchronized (resultUiLock) {
                if (resultUiBuffer.length() == 0) {
                    return;
                }
                chunk = resultUiBuffer.toString();
                resultUiBuffer.setLength(0);
            }
            SwingUtilities.invokeLater(() -> {
                listSearchResultArea.append(chunk);
                int docLen = listSearchResultArea.getDocument().getLength();
                if (docLen < 500_000) {
                    listSearchResultArea.setCaretPosition(docLen);
                }
            });
        };

        int seedIndex = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(seedFile), 1 << 20)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!isListSearchRunning) {
                    break;
                }
                while (isListSearchPaused && isListSearchRunning) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (!isListSearchRunning) {
                    break;
                }

                Long parsed = parseSeedLine(line);
                if (parsed == null) {
                    continue;
                }
                final long seed = parsed;
                seedIndex++;
                final int currentSeedIndex = seedIndex;

                try {
                    submissionThrottle.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!isListSearchRunning) {
                    submissionThrottle.release();
                    break;
                }

                try {
                    listSearchExecutor.submit(() -> {
                        try {
                            if (!isListSearchRunning) {
                                return;
                            }

                            currentListSeed = seed;
                            SearchCoords searcher = new SearchCoords(gameVersion, worldPresetMode);
                            if (isListSearchPaused) {
                                searcher.pause();
                            }
                            activeListSearchers.add(searcher);

                            Consumer<String> seedResultCallback = result -> {
                                synchronized (seedResults) {
                                    seedResults.computeIfAbsent(seed, k -> new ArrayList<>()).add(result);
                                }
                            };

                            boolean checkGeneration = isListSearchPreciseGenerationCheckEffective();
                            Consumer<SearchCoords.ProgressInfo> seedProgressCallback = null;
                            if (!seedParallel) {
                                seedProgressCallback = info -> {
                                    long now = System.currentTimeMillis();
                                    if (now - lastSeedProgressUpdateTime[0] < SEED_PROGRESS_UPDATE_INTERVAL_MS) {
                                        return;
                                    }
                                    lastSeedProgressUpdateTime[0] = now;

                                    long total = info.total();
                                    long processed = info.processed();
                                    double percentage = info.percentage();

                                    long displayProcessed;
                                    double displayPercentage;
                                    if (total > 0 && (percentage > 100.0 || processed > total)) {
                                        displayProcessed = total;
                                        displayPercentage = 100.0;
                                    } else {
                                        displayProcessed = total > 0 ? Math.min(processed, total) : processed;
                                        displayPercentage = Math.min(100.0, percentage);
                                    }

                                    final long finalDisplayProcessed = displayProcessed;
                                    final long finalTotal = total;
                                    final double finalDisplayPercentage = displayPercentage;
                                    final String seedProgressKey = info.stage() == 1
                                            ? "currentSeed.stage1" : "currentSeed.stage2";
                                    SwingUtilities.invokeLater(() -> {
                                        if (isListSearchRunning) {
                                            listSearchCurrentSeedProgressLabel.setText(
                                                    getString(seedProgressKey, currentSeedIndex, totalSeeds,
                                                            finalDisplayProcessed, finalTotal, finalDisplayPercentage)
                                                    );
                                        }
                                    });
                                };
                            }

                            searcher.startSearch(seed, finalThreadsPerSeed, minX, maxX, minZ, maxZ, maxHeight,
                                    seedProgressCallback, seedResultCallback, checkGeneration);

                            if (isListSearchRunning) {
                                searcher.awaitCompletion();
                            }

                            activeListSearchers.remove(searcher);

                            if (!isListSearchRunning) {
                                return;
                            }

                            final int completedSeeds = listProcessedSeedsRef.incrementAndGet();
                            final double percentage = (double) completedSeeds / totalSeeds * 100.0;
                            final long currentLoopTime = System.currentTimeMillis();
                            if (currentLoopTime - lastTotalProgressUpdateTime[0] >= 100
                                    || completedSeeds == totalSeeds) {
                                lastTotalProgressUpdateTime[0] = currentLoopTime;
                                SwingUtilities.invokeLater(() -> {
                                    listSearchProgressBar.setValue(completedSeeds);
                                    listSearchProgressBar.setString(
                                            getString("progress.total", completedSeeds, totalSeeds, percentage));
                                    if (seedParallel && isListSearchRunning) {
                                        listSearchCurrentSeedProgressLabel.setText(
                                                getString("currentSeed.concurrent", lastListConcurrentSeeds));
                                    }
                                });
                            }

                            List<String> results;
                            synchronized (seedResults) {
                                results = seedResults.get(seed);
                            }
                            if (results != null && !results.isEmpty()) {
                                synchronized (resultUiLock) {
                                    resultUiBuffer.append(seed).append('\n');
                                    for (String result : results) {
                                        resultUiBuffer.append(result).append('\n');
                                    }
                                }
                                long now = System.currentTimeMillis();
                                if (now - lastResultUiFlushTime[0] >= LIST_RESULT_UI_FLUSH_MS
                                        || completedSeeds == totalSeeds) {
                                    lastResultUiFlushTime[0] = now;
                                    flushResultUi.run();
                                }
                            }
                        } finally {
                            submissionThrottle.release();
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    submissionThrottle.release();
                    break;
                }
            }
        }

        flushResultUi.run();

        listSearchExecutor.shutdown();
        try {
            while (isListSearchRunning && !listSearchExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                // wait
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        flushResultUi.run();

        if (!isListSearchRunning) {
            return;
        }

        long finalPausedTime = pausedTimeRef[0];
        if (pauseStartTimeRef[0] > 0) {
            finalPausedTime += System.currentTimeMillis() - pauseStartTimeRef[0];
        }
        final long finalElapsedMs = System.currentTimeMillis() - startTime - finalPausedTime;
        SwingUtilities.invokeLater(() -> {
            isListSearchRunning = false;
            isListSearchPaused = false;
            listSearchStartButton.setEnabled(true);
            listSearchPauseButton.setEnabled(false);
            listSearchPauseButton.setText(getString("button.pause"));
            listSearchStopButton.setEnabled(false);
            listSearchResetButton.setEnabled(true);
            listSearchSeedFileButton.setEnabled(true);
            listSearchThreadCountField.setEnabled(true);
            listMaxHeightComboBox.setEnabled(true);
            listVersionComboBox.setEnabled(true);
            listWorldPresetComboBox.setEnabled(true);
            applyListBoundFieldEnableState(true);
            updateListSearchPreciseGenerationCheckUi();
            listSearchProgressBar.setValue((int) Math.min(totalSeeds, Integer.MAX_VALUE));
            listSearchProgressBar.setString(getString("progress.totalComplete", totalSeeds, totalSeeds));
            listSearchCurrentSeedProgressLabel.setText(getString("currentSeed.complete"));
            listSearchElapsedTimeLabel.setText(getString("elapsedTime", formatTime(finalElapsedMs)));
            listSearchRemainingTimeLabel.setText(getString("remainingTime.completed"));
        });
    }

    private static int computeConcurrentSeeds(long area, int threadCount) {
        if (threadCount < 1) {
            return 1;
        }
        // 中小范围（如边长 257≈6.6 万格）：多种子×1 线程，避免「单种子吃满线程」导致每种子创建十几个 OS 线程
        if (area <= LIST_SEED_PARALLEL_MAX_AREA) {
            return threadCount;
        }
        return (int) Math.min(threadCount,
                Math.max(1L, threadCount / Math.max(1L, area / TARGET_CELLS_PER_THREAD)));
    }

    private static int computeThreadsPerSeed(int threadCount, int concurrentSeeds) {
        return Math.max(1, threadCount / Math.max(1, concurrentSeeds));
    }

    private void stopAllListSearchers() {
        synchronized (activeListSearchers) {
            for (SearchCoords s : activeListSearchers) {
                s.stop();
            }
        }
    }

    private void toggleListSearchPause() {
        if (!isListSearchRunning) {
            return;
        }

        if (isListSearchPaused) {
            synchronized (activeListSearchers) {
                for (SearchCoords s : activeListSearchers) {
                    s.resume();
                }
            }
            isListSearchPaused = false;
            listSearchPauseButton.setText(getString("button.pause"));
            listSearchThreadCountField.setEnabled(false);
        } else {
            synchronized (activeListSearchers) {
                for (SearchCoords s : activeListSearchers) {
                    s.pause();
                }
            }
            isListSearchPaused = true;
            listSearchPauseButton.setText(getString("button.resume"));
            listSearchThreadCountField.setEnabled(true);
        }
    }

    private void stopListSearch() {
        stopAllListSearchers();
        if (listSearchExecutor != null) {
            listSearchExecutor.shutdownNow();
        }
        isListSearchRunning = false;
        isListSearchPaused = false;
        listSearchStartButton.setEnabled(true);
        listSearchPauseButton.setEnabled(false);
        listSearchPauseButton.setText(getString("button.pause"));
        listSearchStopButton.setEnabled(false);
        listSearchResetButton.setEnabled(true);
        listSearchSeedFileButton.setEnabled(true);
        listSearchThreadCountField.setEnabled(true);
        listMaxHeightComboBox.setEnabled(true);
        listVersionComboBox.setEnabled(true);
        listWorldPresetComboBox.setEnabled(true);
        applyListBoundFieldEnableState(true);
        updateListSearchPreciseGenerationCheckUi();
    }

    private void resetListSearchToDefaults() {
        long listDefaultSide = (long) DEFAULT_LIST_MAX_X - DEFAULT_LIST_MIN_X + 1;
        if (listSquareSideCheckBox != null) {
            listSquareSideCheckBox.setSelected(true);
        }
        if (listSquareSideField != null) {
            listSquareSideUpdating = true;
            listSquareSideField.setText(String.valueOf(listDefaultSide));
            listSquareSideUpdating = false;
        }
        listMinXField.setText(String.valueOf(DEFAULT_LIST_MIN_X));
        listMaxXField.setText(String.valueOf(DEFAULT_LIST_MAX_X));
        listMinZField.setText(String.valueOf(DEFAULT_LIST_MIN_Z));
        listMaxZField.setText(String.valueOf(DEFAULT_LIST_MAX_Z));
        applyListBoundFieldEnableState(!isListSearchRunning);
        applyListSquareSideToBounds();
    }

    // 解析结果文本，返回种子和坐标的映射
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

            // 尝试解析为种子（长整数）
            try {
                long seed = Long.parseLong(line);
                currentSeed = seed;
                if (!parsedResults.containsKey(seed)) {
                    parsedResults.put(seed, new ArrayList<>());
                }
            } catch (NumberFormatException e) {
                // 不是种子，可能是坐标
                if (line.startsWith("/tp ") && currentSeed != null) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            int x = (int) Double.parseDouble(parts[0]);
                            double y = Double.parseDouble(parts[1]);
                            int z = (int) Double.parseDouble(parts[2]);
                            parsedResults.get(currentSeed).add(new Coordinate(x, y, z, line));
                        } catch (NumberFormatException ex) {
                            // 跳过无效的坐标行
                        }
                    }
                }
            }
        }

        return parsedResults;
    }

    // 坐标类
    private static class Coordinate {
        final int x;
        final double y;
        final int z;
        final String originalLine;
        final boolean canGenerate; // 是否可以生成

        Coordinate(int x, double y, int z, String originalLine) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.originalLine = originalLine;
            this.canGenerate = !originalLine.contains("x");
        }

        // 计算到原点的距离的平方（x² + z²），用于排序
        double distanceSquared() {
            return (double) x * x + (double) z * z;
        }
    }

    // 按最低y排序（如果所有小屋都无法生成则排到最后，否则按可生成的最低y排序）
    private void sortListByLowestY() {
        Map<Long, List<Coordinate>> parsedResults = parseListResults();
        if (parsedResults.isEmpty()) {
            return;
        }

        // 创建种子和最低y值的列表，区分可生成和不可生成
        List<Map.Entry<Long, Double>> validSeedYList = new ArrayList<>(); // 有可生成小屋的种子
        List<Map.Entry<Long, Double>> invalidSeedYList = new ArrayList<>(); // 所有小屋都无法生成的种子

        for (Map.Entry<Long, List<Coordinate>> entry : parsedResults.entrySet()) {
            List<Coordinate> coords = entry.getValue();
            // 检查是否有可生成的小屋
            boolean hasValid = coords.stream().anyMatch(c -> c.canGenerate);

            if (hasValid) {
                // 如果有可生成的小屋，取可生成小屋中的最低y
                double minY = coords.stream()
                        .filter(c -> c.canGenerate)
                        .mapToDouble(c -> c.y)
                        .min()
                        .orElse(Double.MAX_VALUE);
                validSeedYList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minY));
            } else {
                // 如果所有小屋都无法生成，取所有小屋中的最低y（用于在无法生成的种子中排序）
                double minY = coords.stream()
                        .mapToDouble(c -> c.y)
                        .min()
                        .orElse(Double.MAX_VALUE);
                invalidSeedYList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minY));
            }
        }

        // 按最低y值排序（从低到高）
        validSeedYList.sort(Comparator.comparingDouble(Map.Entry::getValue));
        invalidSeedYList.sort(Comparator.comparingDouble(Map.Entry::getValue));

        // 重新构建结果文本：先可生成的种子，后无法生成的种子
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

    // 按距离原点由近到远排序（如果所有小屋都无法生成则排到最后，否则按可生成的最近距离排序）
    private void sortListByDistance() {
        Map<Long, List<Coordinate>> parsedResults = parseListResults();
        if (parsedResults.isEmpty()) {
            return;
        }

        // 创建种子和最近距离的列表，区分可生成和不可生成
        List<Map.Entry<Long, Double>> validSeedDistanceList = new ArrayList<>(); // 有可生成小屋的种子
        List<Map.Entry<Long, Double>> invalidSeedDistanceList = new ArrayList<>(); // 所有小屋都无法生成的种子

        for (Map.Entry<Long, List<Coordinate>> entry : parsedResults.entrySet()) {
            List<Coordinate> coords = entry.getValue();
            // 检查是否有可生成的小屋
            boolean hasValid = coords.stream().anyMatch(c -> c.canGenerate);

            if (hasValid) {
                // 如果有可生成的小屋，取可生成小屋中的最近距离
                double minDistanceSquared = coords.stream()
                        .filter(c -> c.canGenerate)
                        .mapToDouble(Coordinate::distanceSquared)
                        .min()
                        .orElse(0.0);
                validSeedDistanceList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minDistanceSquared));
            } else {
                // 如果所有小屋都无法生成，取所有小屋中的最近距离（用于在无法生成的种子中排序）
                double minDistanceSquared = coords.stream()
                        .mapToDouble(Coordinate::distanceSquared)
                        .min()
                        .orElse(0.0);
                invalidSeedDistanceList.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), minDistanceSquared));
            }
        }

        // 按距离排序（从近到远，即从小到大）
        validSeedDistanceList.sort(Comparator.comparingDouble(Map.Entry::getValue));
        invalidSeedDistanceList.sort(Comparator.comparingDouble(Map.Entry::getValue));

        // 重新构建结果文本：先可生成的种子，后无法生成的种子
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
            JOptionPane.showMessageDialog(this, getString("error.noResultsToExport"), getString("prompt.information"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(getString("dialog.exportResults"));
        fileChooser.setFileFilter(new FileNameExtensionFilter(getString("dialog.textFiles"), "txt"));
        fileChooser.setSelectedFile(new File(getString("file.searchOutput")));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.print(listSearchResultArea.getText());
                JOptionPane.showMessageDialog(this, getString("success.export"), getString("prompt.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, getString("error.exportFailed", e.getMessage()), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // 导出种子列表（不含/tp坐标）
    private void exportSeedList() {
        if (listSearchResultArea.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, getString("error.noResultsToExport"), getString("prompt.information"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 解析结果，提取所有种子
        String text = listSearchResultArea.getText().trim();
        String[] lines = text.split("\n");
        List<Long> seeds = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 跳过/tp开头的坐标行
            if (line.startsWith("/tp ")) {
                continue;
            }
            // 尝试解析为种子
            try {
                long seed = Long.parseLong(line);
                if (!seeds.contains(seed)) {
                    seeds.add(seed);
                }
            } catch (NumberFormatException e) {
                // 忽略无效行
            }
        }

        if (seeds.isEmpty()) {
            JOptionPane.showMessageDialog(this, getString("error.noSeedsFound"), getString("prompt.information"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(getString("dialog.exportSeedList"));
        fileChooser.setFileFilter(new FileNameExtensionFilter(getString("dialog.textFiles"), "txt"));
        fileChooser.setSelectedFile(new File(getString("file.seedList")));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                for (Long seed : seeds) {
                    writer.println(seed);
                }
                JOptionPane.showMessageDialog(this, getString("success.exportSeeds", seeds.size()), getString("prompt.success"), JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, getString("error.exportFailed", e.getMessage()), getString("prompt.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void main(String[] args) {
        // 注意：系统属性应该在 Launcher 中设置
        // 这里只保留必要的初始化逻辑
        // 在主线程中预先初始化 SeedCheckerSettings，避免在多线程环境中初始化
        // 使用 try-catch 来捕获可能的初始化错误，但继续执行程序
        try {
            SeedCheckerInitializer.initialize();
        } catch (ExceptionInInitializerError e) {
            // 如果初始化失败，打印警告但继续执行
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