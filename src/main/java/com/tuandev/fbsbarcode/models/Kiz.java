package com.tuandev.fbsbarcode.models;

public class Kiz {
    private int id;
    private String code;
    private String reservationToken;

    public Kiz() {
    }

    public Kiz(int id, String code) {
        this(id, code, null);
    }

    public Kiz(int id, String code, String reservationToken) {
        this.id = id;
        this.code = code;
        this.reservationToken = reservationToken;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReservationToken() {
        return reservationToken;
    }

    public void setReservationToken(String reservationToken) {
        this.reservationToken = reservationToken;
    }

    @Override
    public String toString() {
        return "Kiz{" +
                "id=" + id +
                ", code='" + code + '\'' +
                ", reservationToken='" + reservationToken + '\'' +
                '}';
    }
}
