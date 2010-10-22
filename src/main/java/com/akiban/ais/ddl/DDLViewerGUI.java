package com.akiban.ais.ddl;

import com.akiban.ais.model.AkibaInformationSchema;
import com.akiban.ais.model.staticgrouping.Grouping;
import com.akiban.ais.model.staticgrouping.GroupsBuilder;
import com.akiban.util.MySqlStatementSplitter;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;

public class DDLViewerGUI {
    private final String STATUS_FORMATTER = "%d table%s in %d group%s";
    private final StringBuilder sqlText = new StringBuilder();
    
    /**
     * Gets stdin as a Reader.
     * @return the reader
     */
    Reader readStdin() {
        return new InputStreamReader(System.in);
    }

    /**
     * Gets a Reader for the given file
     * @param name the file name
     * @return the reader
     * @throws FileNotFoundException if the file wasn't found
     */
    Reader readFile(String name) throws FileNotFoundException {
        return new FileReader(name);
    }

    /**
     * Shows the GUI.
     */
    void showWindow() {
        JFrame window = new JFrame("Grouping Viewer");

        window.addWindowListener( new WindowAdapter(){
            @Override
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });

        window.add( makeRootComponent() );
        window.setSize(700, 500);
        window.setVisible(true);
    }

    /**
     * Writes the output to stdout.
     */
    void writeStdout() {
        try {
            System.out.println( getGrouping() );
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    private boolean appendComment(String... strings) {
        assert strings != null;
        assert strings.length > 0;

        if (sqlText.length() > 0) {
            sqlText.append("\n\n\n");
        }
        sqlText.append("-- ");
        for (String string : strings) {
            sqlText.append(string);
        }
        sqlText.append(":\n\n");
        return true;
    }

    final boolean appendSqlText(Reader in) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(in);
        for (String line = reader.readLine();
                line != null;
                line = reader.readLine())
        {
            builder.append(line);
        }

        boolean readSomething = builder.toString().trim().length() > 0;
        sqlText.append(builder);
        return readSomething;
    }

    final String getSqlText() {
        return sqlText.toString();
    }

    final Grouping getGrouping() throws Exception {
        StringBuilder createStatements = new StringBuilder(sqlText.length());
        MySqlStatementSplitter splitter = new MySqlStatementSplitter(
                new StringReader(sqlText.toString()),
                " ", false, true, "create table "
        );

        createStatements.append("use NONE;");
        for (String createStatement : splitter) {
            createStatements.append(createStatement);
        }

        AkibaInformationSchema ais = new DDLSource().buildAISFromString(createStatements.toString());
        return GroupsBuilder.fromAis(ais, "NONE");
    }

    private JComponent makeRootComponent() {
        final JTextArea left = new JTextArea();
        final JTextArea right = new JTextArea();

        left.setEditable(true);
        right.setEditable(true);

        JPanel ret = new JPanel();
        ret.setLayout( new BorderLayout() );

        Box topBar = Box.createHorizontalBox();
        final SpinnerNumberModel spinnerModel = new SpinnerNumberModel(left.getFont().getSize(), 1, 64, 1);
        final JSpinner spinner = new JSpinner( spinnerModel );
        topBar.add(new JLabel("Adjust font "));
        topBar.add(spinner);
        spinner.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                fontSize(spinnerModel.getNumber().intValue(), left);
                fontSize(spinnerModel.getNumber().intValue(), right);
            }
        });

        JSplitPane mainBox = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainBox.add(new JScrollPane(left));
        mainBox.add(new JScrollPane(right));

        final JLabel bottom = new JLabel(String.format(STATUS_FORMATTER, 0, 's', 0, 's'));

        JPanel topBarWrapper = new JPanel(new BorderLayout());
        topBarWrapper.add(topBar, BorderLayout.WEST);
        ret.add(topBarWrapper, BorderLayout.NORTH);
        ret.add(mainBox, BorderLayout.CENTER);
        ret.add(bottom, BorderLayout.SOUTH);

        left.setText(getSqlText());

        left.getDocument().addDocumentListener(new DocumentListener(){
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh(left, right, bottom);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh(left, right, bottom);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh(left, right, bottom);
            }
        });

        refresh(left, right, bottom);

        return ret;
    }

    private void fontSize(int adjust, JTextArea textArea) {
        Font oldFont = textArea.getFont();
        Font newFont = oldFont.deriveFont(oldFont.getStyle(), adjust);
        textArea.setFont(newFont);
        textArea.invalidate();
    }

    private void refresh(JTextArea sqlArea, JTextArea groupingArea, JLabel status) {
        String out;
        try {

            sqlText.setLength(0);
            sqlText.append(sqlArea.getText());

            Grouping grouping = getGrouping();
            out = grouping.toString();
            groupingArea.setForeground(Color.BLACK);
            int tables = grouping.getTables().size();
            int groups = grouping.getGroups().size();
            status.setText(String.format(STATUS_FORMATTER,
                    tables, tables == 1 ? "" : "s",
                    groups, groups == 1 ? "" : "s"
            ));
        }
        catch (Throwable e) {
            StringWriter err = new StringWriter();
            e.printStackTrace(new PrintWriter(err));
            err.flush();
            out = err.toString();
            groupingArea.setForeground(Color.RED);
        }
        groupingArea.setText(out);
    }

    public final void start(String... args) throws IOException {
        if (args == null) {
            throw new NullPointerException();
        }
        boolean show = false;

        boolean sawStdin = false;
        // Stdin if available. show will be whether there was NO stdin
        Reader in = readStdin();
        try {
            if (in.ready()) {
                appendComment("stdin");
                sawStdin = appendSqlText(in);
                show = !sawStdin;
            }
        }
        finally {
            in.close();
        }

//        // No stdin or args
        if (!sawStdin && args.length == 0) {
            show = true;
        }
        // Process args
        else {
            for (String arg : args) {
                if (arg.equals("--gui")) {
                    show = true;
                }
                else {
                    Reader fileReader = readFile(arg);
                    try {
                        appendComment("file ", arg);
                        appendSqlText(fileReader);
                    } finally {
                        fileReader.close();
                    }
                }
            }
        }

        if (show) {
            showWindow();
        }
        else {
            writeStdout();
        }
    }
    
    public static void main(String[] args) throws Exception {
        new DDLViewerGUI().start(args);
    }
}
