package com.google.gerrit.pgm.wandisco;

import com.google.gerrit.common.Die;
import com.google.gerrit.pgm.init.api.ConsoleUI;

import javax.inject.Inject;

public class AcknowledgeCorrectState implements UninstallStep {

    private final ConsoleUI ui;

    @Inject
    public AcknowledgeCorrectState(ConsoleUI ui) {
        this.ui = ui;
    }

    @Override
    public void run() throws Die {
        boolean answer = ui.yesno(false, "Please confirm you have removed ALL repositories from replication, " +
                                         "including All-Projects and All-Users and you wish to continue?");
        ui.message("\n");

        if (!answer) {
            throw new Die("You have not acknowledged the correct system state. Uninstall process will terminate.");
        }
    }
}
