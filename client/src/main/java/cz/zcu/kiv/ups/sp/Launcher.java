package cz.zcu.kiv.ups.sp;

import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        Logger.getInstance().setLogFile("client.log");
        Application.launch(HelloApplication.class, args);
    }
}
