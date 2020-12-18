/*
 * Copyright (C) 2006-2013 Bitronix Software (http://www.bitronix.be)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bitronix.tm.gui;

import bitronix.tm.journal.TransactionLogRecord;
import bitronix.tm.utils.Decoder;

import javax.swing.*;
import java.awt.*;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Ludovic Orban
 */
public class TransactionLogDialog extends JDialog {

    private final JPanel labelPanel = new JPanel();
    private final JLabel statusLabel = new JLabel("Status");
    private final JLabel recordLengthLabel = new JLabel("Record length");
    private final JLabel headerLengthLabel = new JLabel("Header length");
    private final JLabel timeLabel = new JLabel("Time");
    private final JLabel sequenceNumberLabel = new JLabel("Sequence number");
    private final JLabel crc32Label = new JLabel("CRC");
    private final JLabel gtridLabel = new JLabel("GTRID");
    private final JLabel uniqueNamesLabel = new JLabel("Resources");

    private final JPanel fieldPanel = new JPanel();
    private final JTextField statusField = new JTextField();
    private final JTextField recordLengthField = new JTextField();
    private final JTextField headerLengthField = new JTextField();
    private final JTextField timeField = new JTextField();
    private final JTextField sequenceNumberField = new JTextField();
    private final JTextField crc32Field = new JTextField();
    private final JTextField gtridField = new JTextField();
    private final JTextField uniqueNamesField = new JTextField();


    public TransactionLogDialog(JFrame frame, TransactionLogRecord tlog) {
        super(frame, "Transaction log details", true);


        statusField.setText(Decoder.decodeStatus(tlog.getStatus()));
        recordLengthField.setText(""+tlog.getRecordLength());
        headerLengthField.setText(""+tlog.getHeaderLength());
        timeField.setText(Console.dateFormatter.format(new Date(tlog.getTime())));
        sequenceNumberField.setText(""+tlog.getSequenceNumber());
        if (tlog.isCrc32Correct()) {
            crc32Field.setText(""+tlog.getCrc32());
        }
        else {
            crc32Field.setText(tlog.getCrc32() + " (should be: " + tlog.calculateCrc32() + ")");
            crc32Field.setBackground(Color.RED);
        }
        gtridField.setText(tlog.getGtrid().toString());
        uniqueNamesField.setText(buildString(tlog.getUniqueNames()));

        statusField.setEditable(false);
        recordLengthField.setEditable(false);
        headerLengthField.setEditable(false);
        timeField.setEditable(false);
        sequenceNumberField.setEditable(false);
        crc32Field.setEditable(false);
        gtridField.setEditable(false);
        uniqueNamesField.setEditable(false);


        labelPanel.add(statusLabel); fieldPanel.add(statusField);
        labelPanel.add(recordLengthLabel); fieldPanel.add(recordLengthField);
        labelPanel.add(headerLengthLabel); fieldPanel.add(headerLengthField);
        labelPanel.add(timeLabel); fieldPanel.add(timeField);
        labelPanel.add(sequenceNumberLabel); fieldPanel.add(sequenceNumberField);
        labelPanel.add(crc32Label); fieldPanel.add(crc32Field);
        labelPanel.add(gtridLabel); fieldPanel.add(gtridField);
        labelPanel.add(uniqueNamesLabel); fieldPanel.add(uniqueNamesField);

        labelPanel.setLayout(new GridLayout(8, 1));
        fieldPanel.setLayout(new GridLayout(8, 1));
        getContentPane().add(labelPanel);
        getContentPane().add(fieldPanel);
        getContentPane().setLayout(new BoxLayout(getContentPane(), BoxLayout.X_AXIS));

        pack();
        int xPos = (frame.getBounds().width - 600) / 2;
        int yPos = (frame.getBounds().height - getSize().height) / 2;
        setBounds(xPos, yPos, 600, getSize().height);
    }

    private String buildString(Set uniqueNames) {
        StringBuilder sb = new StringBuilder();

        Iterator it = uniqueNames.iterator();
        while (it.hasNext()) {
            Object o = it.next();
            sb.append(o);

            if (it.hasNext())
                sb.append(", ");
        }

        return sb.toString();
    }

}
