package com.kommserver.launcher.service;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/** What the OS service manager says about the komm-server service. */
@Getter
@Builder
@ToString
public class ServiceStatus {

    private final boolean installed;
    private final boolean running;
    /** The service manager's own word for the state (systemd's {@code active}, Windows' {@code RUNNING}, ...). */
    private final String detail;

    public static ServiceStatus notInstalled() {
        return ServiceStatus.builder().installed(false).running(false).detail("service not installed").build();
    }
}
