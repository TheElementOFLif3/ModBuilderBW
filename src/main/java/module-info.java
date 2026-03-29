module com.ewalab.modbillder {
    requires java.desktop;
    requires javafx.controls;
    requires javafx.swing;
    requires javafx.fxml;

    opens com.ewalab.modbillder to javafx.fxml;
    exports com.ewalab.modbillder;
}
