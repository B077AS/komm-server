package com.kommserver.launcher.service;

public record ServiceStatus(boolean installed, boolean running, String detail) {

    public static ServiceStatus notInstalled() {
        return new ServiceStatus(false, false, "service not installed");
    }
}
