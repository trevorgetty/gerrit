package com.google.gerrit.pgm.wandisco;

import com.google.gerrit.common.Die;

public interface UninstallStep {
    void run() throws Die;
}
