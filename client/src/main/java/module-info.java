module cz.zcu.kiv.ups.sp {
    requires javafx.controls;
    requires javafx.fxml;


    opens cz.zcu.kiv.ups.sp to javafx.fxml;
    exports cz.zcu.kiv.ups.sp;
}