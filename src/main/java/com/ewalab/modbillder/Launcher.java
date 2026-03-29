package com.ewalab.modbillder;

import javafx.application.Application;

import java.util.Locale;

public class Launcher {
    public static void main(String[] args) {
        configureHighDpi();
        Application.launch(HelloApplication.class, args);
    }

    private static void configureHighDpi() {
        System.setProperty("prism.allowhidpi", "true");
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            System.setProperty("sun.java2d.dpiaware", "true");
            System.setProperty("sun.java2d.uiScale.enabled", "true");
            System.setProperty("glass.win.minHiDPI", "1.0");
        }
    }
}
