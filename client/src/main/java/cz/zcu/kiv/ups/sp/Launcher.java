package cz.zcu.kiv.ups.sp;

import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        Logger.getInstance().setLogFile("client.log");
        Logger.info("===== Client application started =====");
        Application.launch(HelloApplication.class, args);
    }
}
