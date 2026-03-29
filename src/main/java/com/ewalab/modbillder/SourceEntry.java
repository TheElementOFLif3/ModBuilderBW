package com.ewalab.modbillder;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.nio.file.Path;

public final class SourceEntry {
    private final Path path;
    private final BooleanProperty included = new SimpleBooleanProperty(true);

    public SourceEntry(Path path, boolean included) {
        this.path = path;
        this.included.set(included);
    }

    public Path getPath() {
        return path;
    }

    public boolean isIncluded() {
        return included.get();
    }

    public void setIncluded(boolean value) {
        included.set(value);
    }

    public BooleanProperty includedProperty() {
        return included;
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
