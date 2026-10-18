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

    String exec(String cmd) = 2;
}
