package com.accessdroid.termux.shell;

// Interfaz del UserService de Shizuku.
//
// No viene en el AAR dev.rikka.shizuku:api: cada app declara la suya, igual que
// hace la demo oficial. El orden y los ids de transacción importan: destroy
// lleva el id reservado por el servidor Shizuku (16777114), y los siguientes
// métodos empiezan a numerarse desde 1 otra vez.
interface ICostPhoneShell {

    void destroy() = 16777114; // reservado por Shizuku (el servidor llama 16777115)

    void exit() = 1;

    String inputTap(int x, int y, long duration) = 2;
    String inputSwipe(int x1, int y1, int x2, int y2, long duration) = 3;
    String inputKeyevent(int keyCode) = 4;
    String screencap(String path) = 5;
    String amStart(String packageName, String className, String action) = 6;
    String globalActionBack() = 7;
    String globalActionHome() = 8;
    String globalActionRecents() = 9;
    String globalActionLockScreen() = 10;
    String globalActionScreenshot() = 11;
    String globalActionPowerDialog() = 12;
}
