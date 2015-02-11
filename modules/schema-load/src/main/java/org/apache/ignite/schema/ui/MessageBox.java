/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.schema.ui;

import javafx.event.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import static org.apache.ignite.schema.ui.Controls.*;

/**
 * Message box functionality.
 */
public class MessageBox extends ModalDialog {
    /** Message box type. */
    private enum MessageType {
        /** Information. */
        INFO,
        /** Warning. */
        WARN,
        /** Error. */
        ERROR,
        /** Confirm. */
        CONFIRM,
        /** Confirm with cancel option. */
        CANCELLABLE_CONFIRM
    }

    /** Message box type. */
    public enum Result {
        /** Return value if YES is chosen. */
        YES,
        /** Return value if YES_TO_ALL is chosen. */
        YES_TO_ALL,
        /** Return value if NO is chosen. */
        NO,
        /** Return value if NO_TO_ALL is chosen. */
        NO_TO_ALL,
        /** Return value if CANCEL is chosen. */
        CANCEL
    }

    /** Dialog result. */
    private Result res = Result.CANCEL;

    /**
     * Create message box.
     *
     * @param owner Owner window.
     * @param type Message box type.
     * @param msg Message to show.
     * @param rememberChoice If {@code true} then add &quot;Remember choice&quot; check box.
     */
    private MessageBox(Stage owner, MessageType type, String msg, final boolean rememberChoice) {
        super(owner, 480, 180);

        String title;
        String iconFile;

        switch (type) {
            case WARN:
                title = "Warning";
                iconFile = "sign_warning";
                break;

            case ERROR:
                title = "Error";
                iconFile = "error";
                break;

            case CONFIRM:
            case CANCELLABLE_CONFIRM:
                title = "Confirmation";
                iconFile = "question";
                break;

            default:
                title = "Information";
                iconFile = "information";
                break;
        }

        setTitle(title);
        initStyle(StageStyle.UTILITY);
        initModality(Modality.APPLICATION_MODAL);
        initOwner(owner);
        setResizable(false);

        GridPaneEx contentPnl = paneEx(10, 10, 0, 10);

        contentPnl.addColumn();
        contentPnl.addColumn(100, 100, Double.MAX_VALUE, Priority.ALWAYS);

        contentPnl.add(hBox(0, true, imageView(iconFile, 48)));

        TextArea ta = new TextArea(msg);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setFocusTraversable(false);

        contentPnl.add(ta);

        final CheckBox rememberChoiceCh = checkBox("Remember choice", "", false);

        if (rememberChoice) {
            contentPnl.skip(1);
            contentPnl.add(rememberChoiceCh);
        }

        HBox btns = hBox(10, true);
        btns.setAlignment(Pos.CENTER);

        if (MessageType.CONFIRM == type || MessageType.CANCELLABLE_CONFIRM == type) {
            res = Result.NO;

            btns.getChildren().addAll(
                button("Yes", "Approve the request", new EventHandler<ActionEvent>() {
                    @Override public void handle(ActionEvent e) {
                        res = rememberChoice && rememberChoiceCh.isSelected() ? Result.YES_TO_ALL : Result.YES;

                        close();
                    }
                }),
                button("No", "Reject the request", new EventHandler<ActionEvent>() {
                    @Override public void handle(ActionEvent e) {
                        res = rememberChoice && rememberChoiceCh.isSelected() ? Result.NO_TO_ALL : Result.NO;

                        close();
                    }
                }));

            if (MessageType.CANCELLABLE_CONFIRM == type)
                btns.getChildren().addAll(
                    button("Cancel", "Cancel the request", new EventHandler<ActionEvent>() {
                        @Override public void handle(ActionEvent e) {
                            res = Result.CANCEL;

                            close();
                        }
                    }));
        }
        else
            btns.getChildren().add(button("OK", "Close dialog", new EventHandler<ActionEvent>() {
                @Override public void handle(ActionEvent e) {
                    close();
                }
            }));

        setScene(scene(borderPane(null, contentPnl, btns, null, null)));
    }

    /**
     * Show message in modal dialog.
     *
     * @param owner Owner window.
     * @param type Message box type.
     * @param msg Message to show.
     * @return Option selected by the user.
     */
    private static Result showDialog(Stage owner, MessageType type, String msg, boolean rememberChoice) {
        MessageBox dlg = new MessageBox(owner, type, msg, rememberChoice);

        dlg.showModal();

        return dlg.res;
    }

    /**
     * Show confirmation dialog.
     *
     * @param owner Owner window.
     * @param msg Message to show.
     * @return {@code true} If user confirm.
     */
    public static boolean confirmDialog(Stage owner, String msg) {
        return showDialog(owner, MessageType.CONFIRM, msg, false) == Result.YES;
    }

    /**
     * Show confirmation dialog.
     *
     * @param owner Owner window.
     * @param msg Message to show.
     * @return User confirmation result.
     */
    public static Result confirmRememberChoiceDialog(Stage owner, String msg) {
        return showDialog(owner, MessageType.CANCELLABLE_CONFIRM, msg, true);
    }

    /**
     * Show information dialog.
     *
     * @param owner Owner window.
     * @param msg Message to show.
     */
    public static void informationDialog(Stage owner, String msg) {
        showDialog(owner, MessageType.INFO, msg, false);
    }

    /**
     * Show warning dialog.
     *
     * @param owner Owner window.
     * @param msg Message to show.
     */
    public static void warningDialog(Stage owner, String msg) {
        showDialog(owner, MessageType.WARN, msg, false);
    }

    /**
     * Show error dialog.
     *
     * @param owner Owner window.
     * @param msg Error message to show.
     * @param e Optional exception to show.
     */
    public static void errorDialog(Stage owner, String msg, Throwable e) {
        String exMsg = e != null ? (e.getMessage() != null ? e.getMessage() : e.getClass().getName()) : null;

        showDialog(owner, MessageType.ERROR, exMsg != null ? msg + "\n" + exMsg : msg, false);
    }
}
