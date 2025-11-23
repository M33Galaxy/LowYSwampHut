package project;

import com.seedfinding.mccore.version.MCVersion;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class LowYSwampHutForFixedSeed extends JFrame {
    // 默认值
    private static final int DEFAULT_MIN_X = -58594;
    private static final int DEFAULT_MAX_X = 58593;
    private static final int DEFAULT_MIN_Z = -58594;
    private static final int DEFAULT_MAX_Z = 58593;
    private static final int DEFAULT_THREAD_COUNT = 8;
    
    private JTextField searchSeedField;
    private JTextField searchThreadCountField;
    private JComboBox<String> maxHeightComboBox;
    private JComboBox<String> versionComboBox;
    private JTextField minXField;
    private JTextField maxXField;
    private JTextField minZField;
    private JTextField maxZField;
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
    
    
    // 加载的字体
    private Font loadedFont = null;

    public LowYSwampHutForFixedSeed() {
        setTitle("Minecraft Java版定种低y女巫小屋搜素工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // 设置窗口图标
        setWindowIcon();
        
        // 设置中文字体
        setChineseFont();

        // 直接添加搜索面板，不再使用标签页
        JPanel searchPanel = createSearchPanel();
        add(searchPanel, BorderLayout.CENTER);
        
        pack();
        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    // 创建搜索面板（第一个功能）
    private JPanel createSearchPanel() {
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
        JLabel seedLabel = new JLabel("种子:");
        seedLabel.setFont(getLoadedFont());
        inputPanel.add(seedLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchSeedField = new JTextField("", 20);
        // 添加输入验证，非整数时提示
        searchSeedField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchSeedField, "种子");
            }
        });
        inputPanel.add(searchSeedField, gbc);

        // Thread Count 输入
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel threadLabel = new JLabel("线程数:");
        threadLabel.setFont(getLoadedFont());
        inputPanel.add(threadLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        searchThreadCountField = new JTextField(String.valueOf(DEFAULT_THREAD_COUNT), 20);
        // 添加输入验证，非整数时提示
        searchThreadCountField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(searchThreadCountField, "线程数");
            }
        });
        inputPanel.add(searchThreadCountField, gbc);

        // 高度筛选下拉框
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        JLabel heightLabel = new JLabel("筛选女巫小屋高度(高度越高搜索越慢):");
        heightLabel.setFont(getLoadedFont());
        inputPanel.add(heightLabel, gbc);
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
        JLabel versionLabel = new JLabel("版本:");
        versionLabel.setFont(getLoadedFont());
        inputPanel.add(versionLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        String[] versionOptions = {"1.21.1", "1.20.1", "1.19.2", "1.18.2"};
        versionComboBox = new JComboBox<>(versionOptions);
        versionComboBox.setSelectedIndex(0); // 默认选择 1.21.1
        inputPanel.add(versionComboBox, gbc);

        // MinX 输入
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
        // 添加输入验证，非整数时提示
        minXField.addFocusListener(new java.awt.event.FocusAdapter() {
            public void focusLost(java.awt.event.FocusEvent e) {
                validateIntegerInput(minXField, "MinX");
            }
        });
        inputPanel.add(minXField, gbc);

        // MaxX 输入
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

        // MinZ 输入
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

        // MaxZ 输入
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

        // 按钮区域
        JPanel buttonPanel = new JPanel(new FlowLayout());
        searchStartButton = new JButton("开始搜索");
        searchPauseButton = new JButton("暂停");
        searchStopButton = new JButton("停止");
        searchResetButton = new JButton("重置搜索区域为世界边界");
        searchPauseButton.setEnabled(false);
        searchStopButton.setEnabled(false);
        buttonPanel.add(searchStartButton);
        buttonPanel.add(searchPauseButton);
        buttonPanel.add(searchStopButton);
        buttonPanel.add(searchResetButton);

        // 静态文字展示区域（放在按钮上方）
        JPanel creditPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        creditPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel creditLabel = new JLabel("<html><div style='text-align: center;'><br><br><br><br><br>每一个种子<br>都有一个低y女巫小屋的梦想<br>——SunnySlopes<br>作者：b站@M33三角座星系<br>字体：江城黑体</div></html>");
        creditLabel.setFont(getLoadedFont()); // 使用加载的字体
        creditPanel.add(creditLabel);

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
        searchProgressBar.setString("进度: 0/0 (0.00%)");
        progressPanel.add(searchProgressBar, pgc);

        pgc.gridwidth = 1;
        pgc.gridy = 1;
        searchElapsedTimeLabel = new JLabel("已过时间: 0天 0时 0分 0秒");
        progressPanel.add(searchElapsedTimeLabel, pgc);

        pgc.gridy = 3;
        searchRemainingTimeLabel = new JLabel("剩余时间: 计算中...");
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
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("检查结果(真实小屋y值可能会处于输出坐标±1格以内，且不能保证每个坐标都能实际生成小屋)"));
        searchResultArea = new JTextArea();
        searchResultArea.setEditable(false);
        searchResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(searchResultArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        JPanel exportSortPanel = new JPanel(new FlowLayout());
        searchExportButton = new JButton("导出");
        searchExportButton.addActionListener(e -> exportSearchResults());
        searchSortButton = new JButton("排序");
        searchSortButton.addActionListener(e -> sortSearchResults());
        exportSortPanel.add(searchExportButton);
        exportSortPanel.add(searchSortButton);
        rightPanel.add(scrollPane, BorderLayout.CENTER);
        rightPanel.add(exportSortPanel, BorderLayout.SOUTH);

        // 使用 JSplitPane 分割
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftContainer, rightPanel);
        splitPane.setDividerLocation(450);
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
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkSearchParameterChange(); }
        });
        
        // 高度筛选变化监听
        maxHeightComboBox.addActionListener(e -> checkSearchParameterChange());
        
        // 坐标变化监听
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
                searchPauseButton.setText("暂停");
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
                searchResultArea.setText("");
                searchProgressBar.setValue(0);
                searchProgressBar.setString("进度: 0/0 (0.00%)");
                searchRemainingTimeLabel.setText("剩余时间: 已重置（参数已更改）");
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
                JOptionPane.showMessageDialog(this, fieldName + "必须为整数", "输入错误", JOptionPane.ERROR_MESSAGE);
                field.requestFocus();
            }
        } catch (NumberFormatException e) {
            // 不是数字，会在开始运行时验证
        }
    }
    
    
    // 排序搜索结果（按y值从低到高，格式为/tp x y z）
    private void sortSearchResults() {
        String text = searchResultArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        
        String[] lines = text.split("\n");
        List<String[]> results = new ArrayList<>();
        List<String> invalidLines = new ArrayList<>();
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 格式：/tp x y z
            if (line.startsWith("/tp ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length >= 3) {
                    try {
                        double y = Double.parseDouble(parts[1]);
                        results.add(new String[]{String.valueOf(y), line});
                    } catch (NumberFormatException e) {
                        invalidLines.add(line);
                    }
                } else {
                    invalidLines.add(line);
                }
            } else {
                invalidLines.add(line);
            }
        }
        
        // 排序：按y值从低到高
        results.sort((a, b) -> {
            double y1 = Double.parseDouble(a[0]);
            double y2 = Double.parseDouble(b[0]);
            return Double.compare(y1, y2);
        });
        
        // 重新组合文本
        StringBuilder sb = new StringBuilder();
        for (String[] result : results) {
            sb.append(result[1]).append("\n");
        }
        for (String invalid : invalidLines) {
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
                    JOptionPane.showMessageDialog(this, "线程数必须大于0", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // 检查线程数是否超过CPU核数
                int cpuThreads = Runtime.getRuntime().availableProcessors();
                if (threadCount > cpuThreads) {
                    int result = JOptionPane.showConfirmDialog(
                        this,
                        "线程数超过CPU核数（" + cpuThreads + "），是否自动调整为" + cpuThreads + "？",
                        "提示",
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
                    MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");
                    
                    // 如果版本变化，需要重新创建searcher
                    if (searcher == null || !searcher.getMCVersion().equals(mcVersion)) {
                        searcher = new SearchCoords(mcVersion);
                    }
                    
                    // 调用startSearch，它会检测到暂停状态并调整线程数
                    String seedText = searchSeedField.getText().trim();
                    long seed = Long.parseLong(seedText);
                    int minX = Integer.parseInt(minXField.getText().trim());
                    int maxX = Integer.parseInt(maxXField.getText().trim());
                    int minZ = Integer.parseInt(minZField.getText().trim());
                    int maxZ = Integer.parseInt(maxZField.getText().trim());
                    
                    searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight, 
                            this::updateSearchProgress, this::addSearchResult);
                    
                    lastSearchThreadCount = threadCount;
                    searchPauseButton.setText("暂停");
                    searchThreadCountField.setEnabled(false);
                    return;
                } else {
                    // 线程数没变化，直接恢复
                    searcher.resume();
                    isSearchPaused = false;
                    searchPauseButton.setText("暂停");
                    searchThreadCountField.setEnabled(false);
                    return;
                }
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
                JOptionPane.showMessageDialog(this, "请输入种子值", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 检查种子是否为整数
            double seedDouble;
            try {
                seedDouble = Double.parseDouble(seedText);
                if (seedDouble != Math.floor(seedDouble)) {
                    JOptionPane.showMessageDialog(this, "种子必须为整数", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "种子格式错误，请输入整数", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 检查种子是否超过MC正常种子边界（绝对值超过2^63-1）
            long seed;
            try {
                seed = Long.parseLong(seedText);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "种子值超出范围（绝对值不能超过2^63-1）", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 验证线程数
            String threadText = searchThreadCountField.getText().trim();
            double threadDouble;
            try {
                threadDouble = Double.parseDouble(threadText);
                if (threadDouble != Math.floor(threadDouble)) {
                    JOptionPane.showMessageDialog(this, "线程数必须为整数", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "线程数格式错误，请输入整数", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            int threadCount = (int) threadDouble;
            if (threadCount < 1) {
                JOptionPane.showMessageDialog(this, "线程数必须大于0", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 检查线程数是否超过CPU核数
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            if (threadCount > cpuThreads) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "线程数超过CPU核数（" + cpuThreads + "），是否自动调整为" + cpuThreads + "？",
                    "提示",
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
                    JOptionPane.showMessageDialog(this, "MinX必须为整数，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                    minXField.setText(String.valueOf(DEFAULT_MIN_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinX格式错误，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                minXField.setText(String.valueOf(DEFAULT_MIN_X));
                return;
            }
            
            try {
                maxXDouble = Double.parseDouble(maxXText);
                if (maxXDouble != Math.floor(maxXDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxX必须为整数，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                    maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxX格式错误，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                maxXField.setText(String.valueOf(DEFAULT_MAX_X));
                return;
            }
            
            try {
                minZDouble = Double.parseDouble(minZText);
                if (minZDouble != Math.floor(minZDouble)) {
                    JOptionPane.showMessageDialog(this, "MinZ必须为整数，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                    minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MinZ格式错误，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                minZField.setText(String.valueOf(DEFAULT_MIN_Z));
                return;
            }
            
            try {
                maxZDouble = Double.parseDouble(maxZText);
                if (maxZDouble != Math.floor(maxZDouble)) {
                    JOptionPane.showMessageDialog(this, "MaxZ必须为整数，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                    maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                    return;
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "MaxZ格式错误，已重置为默认值", "错误", JOptionPane.ERROR_MESSAGE);
                maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
                return;
            }
            
            int minX = (int) minXDouble;
            int maxX = (int) maxXDouble;
            int minZ = (int) minZDouble;
            int maxZ = (int) maxZDouble;
            
            if (minX >= maxX) {
                JOptionPane.showMessageDialog(this, "MinX 必须小于 MaxX", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            if (minZ >= maxZ) {
                JOptionPane.showMessageDialog(this, "MinZ 必须小于 MaxZ", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // 检查世界边界：minX < -58594, maxX > 58593, minZ < -58594, maxZ > 58593
            boolean outOfBounds = minX < DEFAULT_MIN_X || maxX > DEFAULT_MAX_X || minZ < DEFAULT_MIN_Z || maxZ > DEFAULT_MAX_Z;

            if (outOfBounds) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "输入的值超出世界边界，搜索出的小屋可能无法抵达，是否继续？",
                    "警告",
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
            searchPauseButton.setText("暂停");
            searchStopButton.setEnabled(true);
            searchResetButton.setEnabled(false);
            searchSeedField.setEnabled(false);
            searchThreadCountField.setEnabled(false); // 运行中不能修改，暂停时可以修改
            maxHeightComboBox.setEnabled(false);
            versionComboBox.setEnabled(false);
            minXField.setEnabled(false);
            maxXField.setEnabled(false);
            minZField.setEnabled(false);
            maxZField.setEnabled(false);
            searchResultArea.setText("");
            searchProgressBar.setValue(0);
            searchProgressBar.setString("进度: 0/0 (0.00%)");
            searchElapsedTimeLabel.setText("已过时间: 0天 0时 0分 0秒");
            searchRemainingTimeLabel.setText("剩余时间: 计算中...");

            // 获取选择的版本
            String selectedVersion = (String) versionComboBox.getSelectedItem();
            MCVersion mcVersion = getMCVersion(selectedVersion != null ? selectedVersion : "1.21.1");

            searcher = new SearchCoords(mcVersion);
            searcher.startSearch(seed, threadCount, minX, maxX, minZ, maxZ, maxHeight, this::updateSearchProgress, this::addSearchResult);

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的数字", "错误", JOptionPane.ERROR_MESSAGE);
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
            searchPauseButton.setText("暂停");
            searchThreadCountField.setEnabled(false); // 恢复后不能修改线程数
        } else {
            // 暂停
            searcher.pause();
            isSearchPaused = true;
            searchPauseButton.setText("继续");
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
        searchPauseButton.setText("暂停");
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
        searchRemainingTimeLabel.setText("剩余时间: 已停止");
    }

    private void resetSearchToDefaults() {
        minXField.setText(String.valueOf(DEFAULT_MIN_X));
        maxXField.setText(String.valueOf(DEFAULT_MAX_X));
        minZField.setText(String.valueOf(DEFAULT_MIN_Z));
        maxZField.setText(String.valueOf(DEFAULT_MAX_Z));
    }

    private void updateSearchProgress(SearchCoords.ProgressInfo info) {
        SwingUtilities.invokeLater(() -> {
            if (!isSearchRunning) return;

            int progress = (int) Math.min(100, info.percentage);
            searchProgressBar.setValue(progress);
            // 将进度信息显示在进度条中
            searchProgressBar.setString(String.format("进度: %d/%d (%.2f%%)", info.processed, info.total, info.percentage));
            
            // 暂停时不更新时间
            if (!isSearchPaused) {
                searchElapsedTimeLabel.setText("已过时间: " + formatTime(info.elapsedMs));
                if (info.remainingMs > 0) {
                    searchRemainingTimeLabel.setText("剩余时间: " + formatTime(info.remainingMs));
                } else {
                    searchRemainingTimeLabel.setText("剩余时间: 计算中...");
                }
            } else {
                searchRemainingTimeLabel.setText("剩余时间: 已暂停");
            }

            if (info.processed >= info.total) {
                isSearchRunning = false;
                isSearchPaused = false;
                searchStartButton.setEnabled(true);
                searchPauseButton.setEnabled(false);
                searchPauseButton.setText("暂停");
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
                // 不再弹框，只在进度条中显示完成
                searchProgressBar.setString(String.format("进度: %d/%d (100.00%%) - 完成", info.processed, info.total));
                searchRemainingTimeLabel.setText("剩余时间: 已完成");
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
            JOptionPane.showMessageDialog(this, "没有结果可导出", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("导出搜索结果");
        fileChooser.setFileFilter(new FileNameExtensionFilter("文本文件 (*.txt)", "txt"));
        fileChooser.setSelectedFile(new File("search_output.txt"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
                writer.print(searchResultArea.getText());
                JOptionPane.showMessageDialog(this, "导出成功！", "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }


    private String formatTime(long milliseconds) {
        long totalSeconds = milliseconds / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d天 %d时 %d分 %d秒", days, hours, minutes, seconds);
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
            e.printStackTrace();
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
    
    /**
     * 将版本字符串转换为 MCVersion
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

