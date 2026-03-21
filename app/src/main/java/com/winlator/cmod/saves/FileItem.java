package com.winlator.cmod.saves;

import java.io.File;

public class FileItem {
    public final String name;
    public final File file;
    public final boolean isDirectory;
    public final boolean isUp;

    public FileItem(String name, File file, boolean isDirectory, boolean isUp) {
        this.name = name;
        this.file = file;
        this.isDirectory = isDirectory;
        this.isUp = isUp;
    }

    // Constructor convenience untuk folder biasa
    public FileItem(String name, File file, boolean isDirectory) {
        this(name, file, isDirectory, false);
    }
}