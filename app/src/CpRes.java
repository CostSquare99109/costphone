package com.accessdroid.termux;

/**
 * Resultado de una orden de la app.
 *
 * Canal socket: el cuerpo (text o payload binario) viaja en la respuesta.
 * Canal broadcast (legacy): si {@link #fileName} no es null, el cuerpo se
 * guarda además en ese fichero de /sdcard/Download, igual que antes.
 */
final class CpRes {
    static final int OK = 0;
    static final int FAIL = 1;
    static final int EMPTY = 2;

    int code = OK;
    String message = "";
    byte[] payload;          // carga binaria (imagen) si binary = true
    String text;             // carga de texto
    boolean binary;

    String fileName;         // fichero donde el canal legacy guarda la carga
    String mime = "text/plain";

    static CpRes ok(String msg) {
        CpRes r = new CpRes();
        r.code = OK;
        r.message = msg == null ? "" : msg;
        return r;
    }

    static CpRes fail(String msg) {
        CpRes r = new CpRes();
        r.code = FAIL;
        r.message = msg == null ? "" : msg;
        return r;
    }

    static CpRes empty(String msg) {
        CpRes r = new CpRes();
        r.code = EMPTY;
        r.message = msg == null ? "" : msg;
        return r;
    }

    CpRes withText(String t) { this.text = t; return this; }

    CpRes withFile(String name) { this.fileName = name; return this; }

    CpRes withBinary(byte[] data) { this.payload = data; this.binary = true; return this; }

    static CpRes binary(byte[] data) {
        CpRes r = new CpRes();
        r.payload = data;
        r.binary = true;
        return r;
    }
}
